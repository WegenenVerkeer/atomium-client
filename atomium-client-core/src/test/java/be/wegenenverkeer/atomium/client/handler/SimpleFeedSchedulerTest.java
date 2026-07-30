package be.wegenenverkeer.atomium.client.handler;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * Tests that the {@link SimpleFeedScheduler} periodically gives every <em>active</em> feed a run trigger (the tick;
 * the runner itself guards the effective timing), leaves an inactive feed alone, and cancels the tick on
 * {@link SimpleFeedScheduler#stop()}.
 */
class SimpleFeedSchedulerTest {

    /** Short tick interval so the test does not have to wait for the 1s default. */
    private static final Duration TICK = Duration.ofMillis(20);

    @Test
    void ticksActiveFeedsPeriodicallyAndLeavesInactiveAlone() {
        AtomicInteger runsA = new AtomicInteger();
        AtomicInteger runsB = new AtomicInteger();
        AtomicInteger runsInactive = new AtomicInteger();

        // direct (inline) executor -> a tick runs the run synchronously and counts up
        FeedRunner feedA = runner("feed-a", counting(runsA), true);
        FeedRunner feedB = runner("feed-b", counting(runsB), true);
        FeedRunner feedOff = runner("feed-off", counting(runsInactive), false);
        Feeds registry = new Feeds(List.of(feed(feedA), feed(feedB), feed(feedOff)));

        SimpleFeedScheduler scheduler = new SimpleFeedScheduler(registry, TICK);
        try {
            scheduler.start();
            assertThat(scheduler.isRunning()).isTrue();

            // both active feeds are ticked repeatedly
            awaitUntil(() -> runsA.get() >= 3 && runsB.get() >= 3);
            // the inactive feed is never started
            assertThat(runsInactive).hasValue(0);

            scheduler.stop();
            assertThat(scheduler.isRunning()).isFalse();

            // after stop() no more ticks arrive
            int afterStopA = runsA.get();
            awaitAWhile();
            assertThat(runsA.get()).isEqualTo(afterStopA);

            // start() is possible again after a stop()
            scheduler.start();
            awaitUntil(() -> runsA.get() > afterStopA);
        } finally {
            scheduler.close();
        }
        assertThat(scheduler.isRunning()).isFalse();
    }

    /**
     * The tick catches exceptions: one failed trigger (e.g. an executor refusing the submit) must not stop the
     * polling — {@code scheduleWithFixedDelay} would otherwise silently suppress all subsequent executions.
     */
    @Test
    void aThrowingTickDoesNotStopThePolling() {
        AtomicInteger attempts = new AtomicInteger();
        Executor refusingExecutor = runnable -> {
            attempts.incrementAndGet();
            throw new RejectedExecutionException("boom");
        };
        FeedRunner broken = new FeedRunner("feed-broken", Duration.ofMillis(20), counting(new AtomicInteger()),
                refusingExecutor, true, n -> Duration.ofMinutes(1), Clock.systemUTC(), new FeedEventListener() {
        });
        SimpleFeedScheduler scheduler = new SimpleFeedScheduler(new Feeds(List.of(feed(broken))), TICK);
        try {
            scheduler.start();
            // every tick throws; without the try/catch in the tick there would be no second attempt after the first
            awaitUntil(() -> attempts.get() >= 3);
        } finally {
            scheduler.close();
        }
    }

    @Test
    void startAfterCloseFailsWithAClearError() {
        SimpleFeedScheduler scheduler = new SimpleFeedScheduler(new Feeds(List.of()));
        scheduler.close();

        assertThatIllegalStateException().isThrownBy(scheduler::start).withMessageContaining("close()");
    }

    /** A second {@code start()} is a no-op: after {@code stop()} no second tick is left scheduled. */
    @Test
    void aSecondStartSchedulesNothingTwice() {
        AtomicInteger runs = new AtomicInteger();
        SimpleFeedScheduler scheduler = new SimpleFeedScheduler(
                new Feeds(List.of(feed(runner("feed-a", counting(runs), true)))), TICK);
        try {
            scheduler.start();
            scheduler.start();
            awaitUntil(() -> runs.get() >= 2);
            scheduler.stop();
            int afterStop = runs.get();
            awaitAWhile();
            assertThat(runs.get()).isEqualTo(afterStop);
        } finally {
            scheduler.close();
        }
    }

    // these feeds always succeed, so the backoff/clock/listeners are trivial here
    private static FeedRunner runner(String feedId, FeedConsumer consumer, boolean activeOnStartup) {
        return new FeedRunner(feedId, Duration.ofMillis(20), consumer, Runnable::run, activeOnStartup,
                n -> Duration.ofMinutes(1), Clock.systemUTC(), new FeedEventListener() {
        });
    }

    private static FeedConsumer counting(AtomicInteger counter) {
        return isInterrupted -> counter.incrementAndGet();
    }

    // the scheduler only touches the runner; the feed definition and pusher are irrelevant here
    private static FeedRuntime feed(FeedRunner runner) {
        return TestFeedRuntimes.withRunnerOnly(runner);
    }

    private static void awaitUntil(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("condition not reached within the timeout");
            }
            Thread.onSpinWait();
        }
    }

    private static void awaitAWhile() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
