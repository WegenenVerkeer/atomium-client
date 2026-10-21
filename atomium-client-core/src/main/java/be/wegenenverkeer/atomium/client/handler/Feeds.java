package be.wegenenverkeer.atomium.client.handler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Registry of the {@link FeedRuntime}s, one per assembled feed. The scheduler and management tooling (e.g. an
 * admin endpoint) talk to the feeds through this registry. Sorted by {@code feedId} so that the order
 * (in the admin output, among others) is deterministic and logical.
 *
 * <p>It also carries the <b>runtime-wide {@code enabled} flag</b>: does this instance of the application consume
 * feeds at all? An application with several instances uses it to choose the consuming one, at startup or at
 * run time ({@link #enable()} / {@link #disable()}), without touching the per-feed {@code active} state. A feed
 * is consumed when it is {@code active} and the runtime is {@code enabled}; see {@link FeedRunner}. The registry
 * owns the flag and mirrors it onto every runner it holds: a runner starts disabled and runs only once its
 * registry enables it.
 */
public final class Feeds {

    private static final Logger LOG = LoggerFactory.getLogger(Feeds.class);

    private final Map<String, FeedRuntime> feeds = new TreeMap<>();
    private final Clock clock;
    private final Object switchLock = new Object();
    private volatile boolean enabled;

    /** An enabled registry: this instance consumes its active feeds right away. */
    public Feeds(List<FeedRuntime> feeds) {
        this(feeds, true);
    }

    /**
     * @param enabledOnStartup whether this instance consumes feeds from the start; {@code false} for an instance
     *                         that waits for {@link #enable()} (a standby in a leader election)
     */
    public Feeds(List<FeedRuntime> feeds, boolean enabledOnStartup) {
        this(feeds, enabledOnStartup, Clock.systemDefaultZone());
    }

    /**
     * @param clock the clock behind the bounded wait of {@link #disableAndAwait}; give it the clock of the runtimes
     *              ({@link FeedRuntime#of(Feed, Clock)}), so that every time-based decision reads one clock
     * @throws IllegalStateException on two feeds with the same feedId (almost certainly a copy-paste error in
     *                               {@code getFeedId()}) — fail-fast instead of silently letting one disappear
     */
    public Feeds(List<FeedRuntime> feeds, boolean enabledOnStartup, Clock clock) {
        this.clock = clock;
        for (FeedRuntime feed : feeds) {
            FeedRuntime existing = this.feeds.putIfAbsent(feed.feedId(), feed);
            if (existing != null) {
                throw new IllegalStateException(
                        "duplicate feedId '%s': two feeds share the same FeedHandler.getFeedId()".formatted(feed.feedId()));
            }
        }
        this.enabled = false;
        if (enabledOnStartup) {
            enable();
        } else {
            // a disabled start still registers the runners: from now on "not running" is the standby, not a mistake
            feeds.forEach(feed -> feed.runner().setEnabled(false));
            LOG.info("runtime disabled on startup; feeds are not consumed on this instance until enabled");
        }
    }

    public FeedRuntime get(String feedId) {
        var feed = feeds.get(feedId);
        if (feed == null) {
            throw new IllegalArgumentException(
                    "no registered feed for '%s'; known feeds: %s".formatted(feedId, feeds.keySet()));
        }
        return feed;
    }

    public Collection<FeedRuntime> all() {
        return feeds.values();
    }

    /** Whether this instance consumes feeds (the runtime-wide switch); see the class javadoc. */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * This instance consumes feeds from now on: every active feed resumes at the very next tick (a pending wait
     * time or backoff is cleared, like on {@link FeedRunner#activate()}). Idempotent.
     */
    public void enable() {
        synchronized (switchLock) {
            if (enabled) {
                return;
            }
            LOG.info("runtime enabled: this instance consumes the active feeds");
            enabled = true;
            for (FeedRuntime feed : feeds.values()) {
                FeedRunner runner = feed.runner();
                runner.setEnabled(true);
                if (runner.isActive()) {
                    LOG.info("feed '{}' active; query interval {}", runner.feedId(), runner.queryInterval());
                } else {
                    // an inactive feed on a consuming instance is usually unintended (except a deliberately
                    // switched-off feed): WARN makes it visible, once, without polluting the run logs
                    LOG.warn("feed '{}' is INACTIVE; it is only consumed after activation", runner.feedId());
                }
            }
        }
    }

    /**
     * This instance no longer consumes feeds: runs in flight stop after their next commit point, no new run
     * starts. Non-blocking; the feeds keep their {@code active} state, so {@link #enable()} resumes exactly the
     * feeds that were wanted. Idempotent. If you need to know that no run is in flight anymore — before another
     * instance may take over — use {@link #disableAndAwait}.
     */
    public void disable() {
        synchronized (switchLock) {
            if (!enabled) {
                return;
            }
            LOG.info("runtime disabled: this instance stops consuming feeds; a run in flight, if any, stops after "
                    + "its next commit point");
            enabled = false;
            feeds.values().forEach(feed -> feed.runner().setEnabled(false));
        }
    }

    /**
     * {@link #disable() Disable} and wait until no run of any feed is in flight anymore: only then may another
     * instance start consuming (e.g. release the leadership only after this returns {@code true}). Returns
     * {@code false} if a run did not stop within the timeout (the runtime is disabled all the same and the run still
     * stops at its next commit point) or if the waiting thread was interrupted (the interrupt status then remains
     * set). A fitting timeout is the largest {@code stopTimeout} of the feeds.
     *
     * @throws IllegalStateException from a feed thread itself (e.g. from a listener callback): there this would
     *                               deadlock — use {@link #disable()} there
     */
    public boolean disableAndAwait(Duration timeout) {
        feeds.values().forEach(feed -> feed.runner().assertNotOnFeedThread("disableAndAwait", "disable()"));
        disable();
        Instant deadline = Instant.now(clock).plus(timeout);
        for (FeedRuntime feed : feeds.values()) {
            if (!feed.runner().awaitStopped(deadline)) {
                return false;
            }
        }
        return true;
    }

    /** Deactivate all feeds, so that runs in flight stop cleanly after their next commit point (at shutdown). */
    public void close() {
        feeds.values().forEach(feed -> feed.runner().deactivate());
    }
}
