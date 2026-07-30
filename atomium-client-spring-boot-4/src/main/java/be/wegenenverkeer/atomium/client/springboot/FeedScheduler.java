package be.wegenenverkeer.atomium.client.springboot;

import be.wegenenverkeer.atomium.client.handler.FeedRunner;
import be.wegenenverkeer.atomium.client.handler.FeedRuntime;
import be.wegenenverkeer.atomium.client.handler.Feeds;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;
import java.util.concurrent.ScheduledFuture;

/**
 * Schedules one lightweight periodic tick (every {@code tickInterval}, default 1 second): it calls
 * {@link FeedRunner#tryToStart()} for every feed. The runner itself guards when it may actually start
 * ({@code query-interval} after a successful run, backoff after a failed one) — so the tick is non-blocking
 * and cheap, and both query interval and backoff are respected at tick resolution. The actual feed run
 * runs on the feed's {@link FeedConfiguration#getExecutor() executor} (default: a dedicated thread per feed).
 *
 * <p>The tick runs on its <em>own</em> single-thread pool that this class itself owns and manages — deliberately not
 * a bean, so that it never interferes with a {@code TaskScheduler} of the application or with Boot's default for
 * {@code @Scheduled}. For tests an external {@link TaskScheduler} can be injected; this class then does not
 * shut it down.
 *
 * <p>As a {@link SmartLifecycle} the scheduling starts when the context has fully started and stops (cancels
 * the tick and shuts down its own pool) before the beans are torn down. The highest phase
 * ({@link SmartLifecycle#DEFAULT_PHASE}) makes it start last and stop first.
 */
public class FeedScheduler implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(FeedScheduler.class);
    private static final Duration DEFAULT_TICK_INTERVAL = Duration.ofSeconds(1);

    private final Feeds feeds;
    private final TaskScheduler taskScheduler;
    private final @Nullable ThreadPoolTaskScheduler ownScheduler; // non-null when we own the pool
    private final Duration tickInterval;
    private @Nullable ScheduledFuture<?> scheduledTick;
    private volatile boolean running;

    public FeedScheduler(Feeds feeds) {
        this.feeds = feeds;
        this.ownScheduler = createOwnScheduler();
        this.taskScheduler = ownScheduler;
        this.tickInterval = DEFAULT_TICK_INTERVAL;
    }

    public FeedScheduler(Feeds feeds, TaskScheduler taskScheduler, Duration tickInterval) {
        this.feeds = feeds;
        this.ownScheduler = null;
        this.taskScheduler = taskScheduler;
        this.tickInterval = tickInterval;
    }

    /** One thread suffices: exactly one fixed-delay task runs, and fixed-delay executions never overlap. */
    private static ThreadPoolTaskScheduler createOwnScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("atomium-scheduler-");
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        if (ownScheduler != null) {
            ownScheduler.initialize();
        }
        for (FeedRuntime feed : feeds.all()) {
            FeedRunner runner = feed.runner();
            if (runner.isActive()) {
                LOG.info("feed '{}' active; polled every {}", runner.feedId(), runner.queryInterval());
            } else {
                // one-shot startup signal: an inactive feed in production is usually unintended (except jobs-pod-only
                // or a deliberately switched-off feed). WARN makes it visible without polluting the run logs.
                LOG.warn("feed '{}' is INACTIVE (active-on-startup=false); it is only consumed after activation",
                        runner.feedId());
            }
        }
        scheduledTick = taskScheduler.scheduleWithFixedDelay(this::tick, tickInterval);
        running = true;
    }

    private void tick() {
        for (FeedRuntime feed : feeds.all()) {
            // the try/catch is essential: an exception from a task makes scheduleWithFixedDelay silently suppress
            // all subsequent executions — one failed trigger would silently stop the polling forever
            try {
                feed.runner().tryToStart();
            } catch (RuntimeException e) {
                LOG.error("feed '{}': scheduler tick failed; the next tick tries again", feed.feedId(), e);
            }
        }
    }

    @Override
    public synchronized void stop() {
        if (scheduledTick != null) {
            scheduledTick.cancel(false);
            scheduledTick = null;
        }
        if (ownScheduler != null) {
            ownScheduler.shutdown();
        }
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
