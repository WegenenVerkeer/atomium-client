package be.wegenenverkeer.atomium.client.springboot;

import be.wegenenverkeer.atomium.client.handler.FeedConsumer;
import be.wegenenverkeer.atomium.client.handler.FeedEventListener;
import be.wegenenverkeer.atomium.client.handler.FeedRunner;
import be.wegenenverkeer.atomium.client.handler.FeedRuntime;
import be.wegenenverkeer.atomium.client.handler.Feeds;
import be.wegenenverkeer.atomium.client.handler.TestFeedRuntimes;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that the {@link FeedScheduler} periodically gives every <em>active</em> feed a run trigger (the tick; the
 * runner itself guards the effective timing), leaves an inactive feed alone, and on {@link FeedScheduler#stop()}
 * cancels the tick and shuts down its own pool.
 */
class FeedSchedulerTest {

    @Test
    void ticksActiveFeedsPeriodicallyAndLeavesInactiveAlone() {
        AtomicInteger runsA = new AtomicInteger();
        AtomicInteger runsB = new AtomicInteger();
        AtomicInteger runsInactive = new AtomicInteger();

        // direct (inline) executor -> a tick executes the run synchronously and increments the count
        FeedRunner feedA = runner("feed-a", counting(runsA), true);
        FeedRunner feedB = runner("feed-b", counting(runsB), true);
        FeedRunner feedOff = runner("feed-off", counting(runsInactive), false);
        Feeds registry = new Feeds(List.of(feed(feedA), feed(feedB), feed(feedOff)));

        ThreadPoolTaskScheduler taskScheduler = new ThreadPoolTaskScheduler();
        taskScheduler.setPoolSize(2);
        taskScheduler.initialize();
        FeedScheduler scheduler = new FeedScheduler(registry, taskScheduler, Duration.ofMillis(20));
        try {
            scheduler.start();
            assertThat(scheduler.isRunning()).isTrue();

            // both active feeds are ticked repeatedly
            awaitUntil(() -> runsA.get() >= 3 && runsB.get() >= 3);
            // the inactive feed is never started
            assertThat(runsInactive).hasValue(0);
        } finally {
            scheduler.stop();
            taskScheduler.shutdown();
        }

        assertThat(scheduler.isRunning()).isFalse();

        // after stop() no more ticks arrive
        int afterStopA = runsA.get();
        awaitAWhile();
        assertThat(runsA.get()).isEqualTo(afterStopA);
    }

    @Test
    void ownPoolIsCreatedOnStartAndShutDownOnStop() {
        Set<Thread> preExisting = schedulerThreads();
        FeedScheduler scheduler = new FeedScheduler(new Feeds(List.of()));
        try {
            scheduler.start();
            awaitUntil(() -> anyOwnThreadAlive(preExisting));
        } finally {
            scheduler.stop();
        }
        awaitUntil(() -> !anyOwnThreadAlive(preExisting));
    }

    @Test
    void ownPoolSurvivesALifecycleRestart() {
        // SmartLifecycle may cycle stop→start (context restart): its own pool must then start up again
        Set<Thread> preExisting = schedulerThreads();
        FeedScheduler scheduler = new FeedScheduler(new Feeds(List.of()));
        try {
            scheduler.start();
            awaitUntil(() -> anyOwnThreadAlive(preExisting));
            scheduler.stop();
            awaitUntil(() -> !anyOwnThreadAlive(preExisting));

            scheduler.start();
            awaitUntil(() -> anyOwnThreadAlive(preExisting));
            assertThat(scheduler.isRunning()).isTrue();
        } finally {
            scheduler.stop();
        }
    }

    /**
     * All live scheduler-pool threads in the JVM. Cached Spring test contexts elsewhere in the suite
     * legitimately keep their own {@link FeedScheduler} (and thus such a thread) alive for the rest of the
     * JVM, so the lifecycle tests must only observe threads created <em>after</em> their own snapshot.
     */
    private static Set<Thread> schedulerThreads() {
        Set<Thread> threads = new HashSet<>(Thread.getAllStackTraces().keySet());
        threads.removeIf(thread -> !thread.getName().startsWith("atomium-scheduler-"));
        return threads;
    }

    /** Is a scheduler thread alive that did not exist at the {@code preExisting} snapshot? */
    private static boolean anyOwnThreadAlive(Set<Thread> preExisting) {
        return schedulerThreads().stream()
                .anyMatch(thread -> !preExisting.contains(thread) && thread.isAlive());
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

    // the scheduler only touches the runner; the feed definition and pusher are not relevant here
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
