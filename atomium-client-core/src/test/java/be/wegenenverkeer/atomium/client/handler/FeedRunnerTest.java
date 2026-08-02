package be.wegenenverkeer.atomium.client.handler;


import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lifecycle unit tests for {@link FeedRunner}, independent of Spring/WireMock: the single-run guard under
 * concurrency, the graceful stop on deactivation during a running run, and the timing of the next run
 * (query interval and backoff, with a fake clock).
 */
class FeedRunnerTest {

    @Test
    void anInactiveFeedStartsNoRun() {
        AtomicInteger runs = new AtomicInteger();
        FeedConsumer consumer = isInterrupted -> runs.incrementAndGet();

        // activeOnStartup = false: tryToStart must do nothing
        FeedRunner runner = runner(consumer, Runnable::run, false);

        assertThat(runner.tryToStart()).isFalse();
        assertThat(runs).hasValue(0);

        // only after explicit activation does a run start (direct executor -> synchronous)
        runner.activate();
        assertThat(runner.tryToStart()).isTrue();
        assertThat(runs).hasValue(1);
    }

    @Test
    void guardAllowsAtMostOneConcurrentRun() throws Exception {
        CountDownLatch runBusy = new CountDownLatch(1);
        CountDownLatch mayFinish = new CountDownLatch(1);
        AtomicInteger runs = new AtomicInteger();
        FeedConsumer consumer = isInterrupted -> {
            runs.incrementAndGet();
            runBusy.countDown();
            awaitLatch(mayFinish); // keep the run "busy" while we hammer tryToStart
        };

        ExecutorService feedThread = Executors.newSingleThreadExecutor();
        ExecutorService hammers = Executors.newFixedThreadPool(16);
        try {
            FeedRunner runner = runner(consumer, feedThread, true);

            int n = 16;
            CyclicBarrier startingLine = new CyclicBarrier(n);
            List<Future<Boolean>> attempts = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                attempts.add(hammers.submit(() -> {
                    startingLine.await();
                    return runner.tryToStart();
                }));
            }

            long started = 0;
            for (Future<Boolean> attempt : attempts) {
                if (attempt.get()) {
                    started++;
                }
            }

            // exactly one attempt started a run, and the reader was invoked exactly once
            assertThat(started).isEqualTo(1);
            assertThat(runBusy.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(runner.isRunning()).isTrue();
            assertThat(runs).hasValue(1);

            mayFinish.countDown();
            awaitUntil(() -> !runner.isRunning());
            assertThat(runs).hasValue(1);
        } finally {
            mayFinish.countDown();
            feedThread.shutdownNow();
            hammers.shutdownNow();
        }
    }

    @Test
    void deactivationStopsTheRunningRunGracefully() throws Exception {
        CountDownLatch runBusy = new CountDownLatch(1);
        // the reader loops until it is interrupted (like a real run that checks after a commit point)
        FeedConsumer consumer = isInterrupted -> {
            runBusy.countDown();
            while (!isInterrupted.getAsBoolean()) {
                Thread.onSpinWait();
            }
        };

        ExecutorService feedThread = Executors.newSingleThreadExecutor();
        try {
            FeedRunner runner = runner(consumer, feedThread, true);

            assertThat(runner.tryToStart()).isTrue();
            assertThat(runBusy.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(runner.isRunning()).isTrue();

            runner.deactivate(); // isInterrupted () -> !active becomes true -> reader returns
            awaitUntil(() -> !runner.isRunning());
            assertThat(runner.isActive()).isFalse();
        } finally {
            feedThread.shutdownNow();
        }
    }

    @Test
    void deactivateAndAwaitOnlyReturnsOnceTheRunHasStopped() throws Exception {
        CountDownLatch runBusy = new CountDownLatch(1);
        FeedConsumer consumer = isInterrupted -> {
            runBusy.countDown();
            while (!isInterrupted.getAsBoolean()) {
                Thread.onSpinWait();
            }
        };

        ExecutorService feedThread = Executors.newSingleThreadExecutor();
        try {
            FeedRunner runner = runner(consumer, feedThread, true);

            assertThat(runner.tryToStart()).isTrue();
            assertThat(runBusy.await(2, TimeUnit.SECONDS)).isTrue();

            assertThat(runner.deactivateAndAwait(Duration.ofSeconds(2))).isTrue();

            // the guarantee that matters: after returning, no run is still busy and none can start
            assertThat(runner.isRunning()).isFalse();
            assertThat(runner.isActive()).isFalse();
            assertThat(runner.tryToStart()).isFalse();
        } finally {
            feedThread.shutdownNow();
        }
    }

    @Test
    void deactivateAndAwaitReturnsFalseWhenTheRunDoesNotReachItsCommitPoint() throws Exception {
        CountDownLatch runBusy = new CountDownLatch(1);
        CountDownLatch mayFinish = new CountDownLatch(1);
        // a reader that has not yet reached its interruption check (e.g. a slow commit)
        FeedConsumer consumer = isInterrupted -> {
            runBusy.countDown();
            awaitLatch(mayFinish);
        };

        ExecutorService feedThread = Executors.newSingleThreadExecutor();
        try {
            FeedRunner runner = runner(consumer, feedThread, true);

            assertThat(runner.tryToStart()).isTrue();
            assertThat(runBusy.await(2, TimeUnit.SECONDS)).isTrue();

            assertThat(runner.deactivateAndAwait(Duration.ofMillis(100))).isFalse();
            assertThat(runner.isActive()).isFalse();   // the deactivation itself does stick
            assertThat(runner.isRunning()).isTrue();   // the run keeps going until its commit point

            mayFinish.countDown();
            awaitUntil(() -> !runner.isRunning());
        } finally {
            feedThread.shutdownNow();
        }
    }

    @Test
    void deactivateAndAwaitFromTheFeedThreadFailsWithAClearError() {
        // the deadlock guard: a listener callback (or handler) that would try this from the feed thread
        // would wait on itself forever — the runner refuses that with a clear failure
        AtomicReference<FeedRunner> runnerRef = new AtomicReference<>();
        AtomicReference<IllegalStateException> caught = new AtomicReference<>();
        FeedConsumer consumer = isInterrupted -> {
            try {
                runnerRef.get().deactivateAndAwait(Duration.ofSeconds(1));
            } catch (IllegalStateException e) {
                caught.set(e);
            }
        };

        ExecutorService feedThread = Executors.newSingleThreadExecutor();
        try {
            FeedRunner runner = runner(consumer, feedThread, true);
            runnerRef.set(runner);

            assertThat(runner.tryToStart()).isTrue();
            awaitUntil(() -> caught.get() != null);
            assertThat(caught.get()).hasMessageContaining("deadlock");
        } finally {
            feedThread.shutdownNow();
        }
    }

    /**
     * Backoff on failed runs (fake clock, inline executor): within the interval {@code tryToStart} refuses,
     * after it it starts; the counter increments; a successful run and {@code activate} reset the backoff; and the
     * {@code runFailed} event carries the counter + the entry context.
     */
    @Nested
    class Backoff {

        private final TestClock clock = new TestClock(Instant.parse("2026-01-01T00:00:00Z"));
        private final FeedBackoffPolicy oneMinute = failures -> Duration.ofMinutes(1);

        @Test
        void refusesWithinTheIntervalAndStartsAfterIt() {
            AtomicInteger runs = new AtomicInteger();
            FeedConsumer alwaysFails = isInterrupted -> {
                runs.incrementAndGet();
                throw new RuntimeException("boom");
            };
            FeedRunner runner = new FeedRunner("feed", Duration.ofMinutes(1), alwaysFails, Runnable::run, true,
                    oneMinute, clock, new FeedEventListener() {
            });

            // run 1 fails → backoff active
            assertThat(runner.tryToStart()).isTrue();
            assertThat(runs).hasValue(1);
            assertThat(runner.consecutiveFailures()).isEqualTo(1);
            assertThat(runner.nextRun())
                    .isEqualTo(OffsetDateTime.now(clock).plus(Duration.ofMinutes(1)).truncatedTo(ChronoUnit.SECONDS));

            // within the interval: refused (no extra run)
            clock.advance(Duration.ofSeconds(59));
            assertThat(runner.tryToStart()).isFalse();
            assertThat(runs).hasValue(1);

            // just after the interval: again (fails again → counter 2)
            clock.advance(Duration.ofSeconds(1));
            assertThat(runner.tryToStart()).isTrue();
            assertThat(runs).hasValue(2);
            assertThat(runner.consecutiveFailures()).isEqualTo(2);
        }

        @Test
        void aSuccessfulRunResetsTheBackoff() {
            AtomicBoolean fail = new AtomicBoolean(true);
            FeedConsumer consumer = isInterrupted -> {
                if (fail.get()) {
                    throw new RuntimeException("boom");
                }
            };
            FeedRunner runner = new FeedRunner("feed", Duration.ofMinutes(1), consumer, Runnable::run, true,
                    oneMinute, clock, new FeedEventListener() {
            });

            assertThat(runner.tryToStart()).isTrue(); // fails → backoff
            assertThat(runner.consecutiveFailures()).isEqualTo(1);

            fail.set(false);
            clock.advance(Duration.ofMinutes(1));
            assertThat(runner.tryToStart()).isTrue(); // succeeds → counter gone, next run = the regular poll

            assertThat(runner.consecutiveFailures()).isZero();
            assertThat(runner.nextRun())
                    .isEqualTo(OffsetDateTime.now(clock).plus(Duration.ofMinutes(1)).truncatedTo(ChronoUnit.SECONDS));
        }

        @Test
        void anInterruptedRunDoesNotResetTheBackoff() {
            AtomicReference<FeedRunner> runnerRef = new AtomicReference<>();
            AtomicBoolean fail = new AtomicBoolean(true);
            // the second run succeeds, but deactivates itself → it gets interrupted instead of ending normally
            FeedConsumer consumer = isInterrupted -> {
                if (fail.get()) {
                    throw new RuntimeException("boom");
                }
                runnerRef.get().deactivate();
            };
            FeedRunner runner = new FeedRunner("feed", Duration.ofMinutes(1), consumer, Runnable::run, true,
                    oneMinute, clock, new FeedEventListener() {
            });
            runnerRef.set(runner);

            assertThat(runner.tryToStart()).isTrue(); // fails → backoff
            assertThat(runner.consecutiveFailures()).isEqualTo(1);

            fail.set(false);
            clock.advance(Duration.ofMinutes(1));
            assertThat(runner.tryToStart()).isTrue(); // runs, but gets interrupted (deactivate)

            // an interrupted run is not a recovery moment: the counter stays (so no "recovered" log)
            assertThat(runner.isActive()).isFalse();
            assertThat(runner.consecutiveFailures()).isEqualTo(1);
        }

        @Test
        void activateResetsTheBackoff() {
            FeedConsumer alwaysFails = isInterrupted -> {
                throw new RuntimeException("boom");
            };
            FeedRunner runner = new FeedRunner("feed", Duration.ofMinutes(1), alwaysFails, Runnable::run, true,
                    oneMinute, clock, new FeedEventListener() {
            });

            assertThat(runner.tryToStart()).isTrue(); // fails → backoff
            assertThat(runner.consecutiveFailures()).isEqualTo(1);

            runner.activate(); // a human intervenes → backoff gone, no more wait time
            assertThat(runner.consecutiveFailures()).isZero();
            assertThat(runner.nextRun()).isNull();
            assertThat(runner.tryToStart()).isTrue(); // allowed immediately, even though the clock has not advanced
        }

        @Test
        void theRunFailedEventCarriesTheCounterAndEntryContext() {
            AtomicReference<FeedRunFailure> received = new AtomicReference<>();
            FeedEventListener listener = new FeedEventListener() {
                @Override
                public void runFailed(FeedRunFailure failure) {
                    received.set(failure);
                }
            };
            FeedConsumer decodeFailure = isInterrupted -> {
                throw new FeedEntryProcessingException("feed", "id-007", FeedEntryPhase.DECODE, new RuntimeException("rotten json"));
            };
            FeedRunner runner = new FeedRunner("feed", Duration.ofMinutes(1), decodeFailure, Runnable::run, true,
                    oneMinute, clock, listener);

            runner.tryToStart();

            FeedRunFailure failure = received.get();
            assertThat(failure.consecutiveFailures()).isEqualTo(1);
            assertThat(failure.entryId()).isEqualTo("id-007");
            assertThat(failure.phase()).isEqualTo(FeedEntryPhase.DECODE);
            assertThat(failure.cause()).hasMessage("rotten json");
            assertThat(failure.nextAttemptAfter())
                    .isEqualTo(OffsetDateTime.now(clock).plus(Duration.ofMinutes(1)).truncatedTo(ChronoUnit.SECONDS));
        }
    }

    /**
     * The query interval after a successful run (fake clock, inline executor): within the interval
     * {@code tryToStart} refuses (a frequent scheduler tick must not drive up the poll frequency), after it
     * it starts; {@code activate} clears the wait time.
     */
    @Nested
    class QueryInterval {

        private final TestClock clock = new TestClock(Instant.parse("2026-01-01T00:00:00Z"));

        private FeedRunner runner(FeedConsumer consumer) {
            return new FeedRunner("feed", Duration.ofMinutes(1), consumer, Runnable::run, true,
                    n -> Duration.ofMinutes(1), clock, new FeedEventListener() {
            });
        }

        @Test
        void waitsOutTheQueryIntervalAfterASuccessfulRun() {
            AtomicInteger runs = new AtomicInteger();
            FeedRunner runner = runner(isInterrupted -> runs.incrementAndGet());

            // the first run is allowed immediately (no wait time at startup)
            assertThat(runner.nextRun()).isNull();
            assertThat(runner.tryToStart()).isTrue();
            assertThat(runs).hasValue(1);
            assertThat(runner.nextRun())
                    .isEqualTo(OffsetDateTime.now(clock).plus(Duration.ofMinutes(1)).truncatedTo(ChronoUnit.SECONDS));

            // within the interval: refused
            clock.advance(Duration.ofSeconds(59));
            assertThat(runner.tryToStart()).isFalse();
            assertThat(runs).hasValue(1);

            // just after the interval: the next poll
            clock.advance(Duration.ofSeconds(1));
            assertThat(runner.tryToStart()).isTrue();
            assertThat(runs).hasValue(2);
        }

        @Test
        void activateClearsTheWaitingTime() {
            AtomicInteger runs = new AtomicInteger();
            FeedRunner runner = runner(isInterrupted -> runs.incrementAndGet());

            assertThat(runner.tryToStart()).isTrue(); // succeeds → wait time until the next poll
            assertThat(runner.tryToStart()).isFalse();

            runner.activate(); // a human intervenes → allowed immediately, even though the clock has not advanced
            assertThat(runner.tryToStart()).isTrue();
            assertThat(runs).hasValue(2);
        }
    }

    /** A manually advanceable clock, so the backoff timing is testable without real wait time. */
    private static final class TestClock extends Clock {
        private Instant now;

        TestClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    // success/failure-agnostic defaults for the non-backoff tests
    private static FeedRunner runner(FeedConsumer consumer, Executor executor, boolean activeOnStartup) {
        return new FeedRunner("feed", Duration.ofMinutes(1), consumer, executor, activeOnStartup,
                failures -> Duration.ofMinutes(1), Clock.systemUTC(), new FeedEventListener() {
        });
    }

    /** Waits (with a timeout) until a condition becomes true, without awaitility on the classpath. */
    private static void awaitUntil(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("condition not reached within the timeout");
            }
            Thread.onSpinWait();
        }
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch not released within the timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
