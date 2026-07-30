package be.wegenenverkeer.atomium.client.handler;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Minimal scheduler for applications without a framework scheduler: one light periodic tick (every
 * {@code tickInterval}, default 1 second; the first one right away) on its own daemon thread ({@code atomium-scheduler})
 * invokes {@link FeedRunner#tryToStart()} for every feed. The runner itself guards when it may actually
 * start (query interval after a successful run, backoff after a failed one) — the tick is thus non-blocking and
 * cheap, and both the query interval and the backoff are respected at tick resolution. The actual feed run
 * executes on the feed's {@link Feed.Builder#executor(java.util.concurrent.Executor) executor}.
 *
 * <p>Call {@link #start()} after assembly and {@link #close()} when shutting down the application
 * (together with {@link Feeds#close()} to let runs in flight stop cleanly). If you already have a scheduler of your own
 * does not need this class: frequently invoking {@code runner().tryToStart()} suffices.
 */
public final class SimpleFeedScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(SimpleFeedScheduler.class);
    private static final Duration DEFAULT_TICK_INTERVAL = Duration.ofSeconds(1);

    private final Feeds feeds;
    private final Duration tickInterval;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "atomium-scheduler");
        thread.setDaemon(true);
        return thread;
    });
    private @Nullable ScheduledFuture<?> scheduledTick;
    private boolean running;

    public SimpleFeedScheduler(Feeds feeds) {
        this(feeds, DEFAULT_TICK_INTERVAL);
    }

    public SimpleFeedScheduler(Feeds feeds, Duration tickInterval) {
        this.feeds = feeds;
        this.tickInterval = tickInterval;
    }

    /**
     * Start scheduling the periodic tick (the first one right away).
     *
     * @throws IllegalStateException after {@link #close()} (this instance is then no longer usable)
     */
    public synchronized void start() {
        if (running) {
            return;
        }
        if (scheduler.isShutdown()) {
            throw new IllegalStateException("this SimpleFeedScheduler is closed (close() has already been called)");
        }
        for (FeedRuntime feed : feeds.all()) {
            FeedRunner runner = feed.runner();
            if (runner.isActive()) {
                LOG.info("feed '{}' active; polled every {}", runner.feedId(), runner.queryInterval());
            } else {
                // one-shot startup signal: an inactive feed in production is usually unintended. WARN makes it
                // visible without polluting the run logs.
                LOG.warn("feed '{}' is INACTIVE (activeOnStartup=false); it is only consumed after activation",
                        runner.feedId());
            }
        }
        scheduledTick = scheduler.scheduleWithFixedDelay(this::tick, 0, tickInterval.toNanos(), TimeUnit.NANOSECONDS);
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

    /** Cancel the tick; {@link #start()} can be called again afterwards. A feed run in flight is not interrupted. */
    public synchronized void stop() {
        if (scheduledTick != null) {
            scheduledTick.cancel(false);
            scheduledTick = null;
        }
        running = false;
    }

    public synchronized boolean isRunning() {
        return running;
    }

    /** {@link #stop()} + wind down the scheduler thread; after this the instance is no longer usable. */
    public synchronized void close() {
        stop();
        scheduler.shutdown();
    }
}
