package be.wegenenverkeer.atomium.client.springboot;

import be.wegenenverkeer.atomium.client.fetch.FeedPointer;
import be.wegenenverkeer.atomium.client.handler.FeedEventListener;
import be.wegenenverkeer.atomium.client.handler.FeedRunFailure;
import be.wegenenverkeer.atomium.client.handler.FeedRunResult;
import be.wegenenverkeer.atomium.client.protocol.FeedPageMetadata;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.TimeGauge;
import org.jspecify.annotations.Nullable;

import java.time.OffsetDateTime;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A {@link FeedEventListener} that translates the feed processing into <a href="https://micrometer.io">Micrometer</a>
 * metrics. Every metric is tagged with {@code feed} (the feedId), so the series are split per feed. The autoconfig
 * ({@code AtomiumMetricsAutoConfiguration}) registers this listener automatically as soon as there is a
 * {@link MeterRegistry} in the context (typically: Spring Boot Actuator + a registry such as prometheus).
 *
 * <p>All callbacks run after the commit point (the contract of {@link FeedEventListener}), so the counters measure
 * what <em>really</em> happened — no rolled-back runs. The entry counters advance per <em>commit</em> (the delta from
 * {@link FeedEventListener#feedPointerAdvanced}): that way you also see progress in the middle of a long run, and the
 * already committed counts are not lost if the run still fails afterwards. What was uncommitted at a failure
 * is only counted when it actually commits on a later attempt — so no double counting.
 *
 * <table><caption>The metrics (all tagged {@code feed})</caption>
 *   <tr><td>{@code atomium.runs} (tag {@code outcome})</td><td>counter</td><td>one run ended ({@code completed}/{@code interrupted}/{@code failed})</td></tr>
 *   <tr><td>{@code atomium.entries.read|accepted|processed}</td><td>counter</td><td>summed per commit; read/accepted count entries, processed is the handler's own measure of realised work (default: entries)</td></tr>
 *   <tr><td>{@code atomium.feed.last.success.time}</td><td>time gauge</td><td>the last moment the feed demonstrably made healthy progress: seeded at activation, advanced on every commit and every completed run (also an empty poll or 304), removed at deactivation. The staleness signal for a "feed consumer down" alert.</td></tr>
 *   <tr><td>{@code atomium.entries.last.commit.time}</td><td>time gauge</td><td>timestamp of the last commit (is the feed still alive?)</td></tr>
 *   <tr><td>{@code atomium.entries.last.event.time}</td><td>time gauge</td><td>{@code updated} of the youngest event a commit covered (how current is the data?)</td></tr>
 *   <tr><td>{@code atomium.pages.fetched}</td><td>counter</td><td>HTTP pages fetched</td></tr>
 *   <tr><td>{@code atomium.polls.not.modified}</td><td>counter</td><td>polls that got {@code 304 Not Modified}</td></tr>
 *   <tr><td>{@code atomium.runs.consecutive.failures}</td><td>gauge</td><td>current number of consecutive failures (0 = healthy)</td></tr>
 *   <tr><td>{@code atomium.after.commit.consecutive.failures}</td><td>gauge</td><td>current number of consecutive {@code afterCommit}-hook failures (0 = healthy): seeded at activation, removed at deactivation — a hook failure does not fail the run, so this gauge is the alerting signal for it</td></tr>
 * </table>
 */
// deliberately in this module despite zero Spring dependencies: we do not consider the listener worth its own
// micro-module (for now); for metrics without Boot, copy the class or pull in this module
public class MicrometerFeedEventListener implements FeedEventListener {

    private static final String TAG_FEED = "feed";

    private final MeterRegistry registry;
    // backing stores for the gauges: one atomic per feed, the gauge reads it live
    private final ConcurrentHashMap<String, AtomicInteger> consecutiveFailures = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> afterCommitFailures = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> lastCommit = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> lastSuccess = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> lastEvent = new ConcurrentHashMap<>();

    public MicrometerFeedEventListener(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void feedActivated(String feedId) {
        // seed at activation: the series exists from the moment the feed is supposed to make progress,
        // so a feed that never gets going alarms by going stale instead of staying invisible
        lastSuccessGauge(feedId).set(registry.config().clock().wallTime());
        afterCommitFailuresGauge(feedId).set(0);
    }

    @Override
    public void feedDeactivated(String feedId) {
        // remove the series: a deliberately stopped feed (admin, leader handover) must not go stale
        if (lastSuccess.remove(feedId) != null) {
            registry.find("atomium.feed.last.success.time").tag(TAG_FEED, feedId).timeGauges()
                    .forEach(registry::remove);
        }
        // same for the hook gauge: a lingering failure streak on an ex-leader pod must not keep alerting
        if (afterCommitFailures.remove(feedId) != null) {
            registry.find("atomium.after.commit.consecutive.failures").tag(TAG_FEED, feedId).gauges()
                    .forEach(registry::remove);
        }
    }

    @Override
    public void afterCommitCompleted(String feedId, @Nullable Throwable failure) {
        // only for an activated feed (feedActivated seeded it): a hook of a run still in flight after a
        // deactivation must not resurrect the removed series
        AtomicInteger failures = afterCommitFailures.get(feedId);
        if (failures == null) {
            return;
        }
        if (failure != null) {
            failures.incrementAndGet();
        } else {
            failures.set(0);
        }
    }

    @Override
    public void pageFetched(String feedId, FeedPageMetadata pageMetadata, int entryCount) {
        registry.counter("atomium.pages.fetched", TAG_FEED, feedId).increment();
    }

    @Override
    public void feedNotModified(String feedId) {
        registry.counter("atomium.polls.not.modified", TAG_FEED, feedId).increment();
    }

    @Override
    public void runCompleted(String feedId, FeedRunResult result) {
        registry.counter("atomium.runs", TAG_FEED, feedId, "outcome", "completed").increment();
        failuresGauge(feedId).set(0);   // a successful run → the feed is healthy again
        touchLastSuccess(feedId);       // also an empty poll or a 304 is healthy progress
    }

    @Override
    public void runInterrupted(String feedId, FeedRunResult result) {
        registry.counter("atomium.runs", TAG_FEED, feedId, "outcome", "interrupted").increment();
        failuresGauge(feedId).set(0);   // a clean interruption is not a failure
    }

    @Override
    public void runFailed(FeedRunFailure failure) {
        registry.counter("atomium.runs", TAG_FEED, failure.feedId(), "outcome", "failed").increment();
        failuresGauge(failure.feedId()).set(failure.consecutiveFailures());
    }

    @Override
    public void feedPointerAdvanced(String feedId, FeedPointer feedPointer, FeedRunResult sincePreviousCommit,
                                    @Nullable OffsetDateTime latestEventUpdated) {
        registry.counter("atomium.entries.read", TAG_FEED, feedId).increment(sincePreviousCommit.read());
        registry.counter("atomium.entries.accepted", TAG_FEED, feedId).increment(sincePreviousCommit.accepted());
        registry.counter("atomium.entries.processed", TAG_FEED, feedId).increment(sincePreviousCommit.processed());
        lastCommitGauge(feedId).set(registry.config().clock().wallTime());
        touchLastSuccess(feedId);
        if (latestEventUpdated != null) {
            lastEventGauge(feedId).set(latestEventUpdated.toInstant().toEpochMilli());
        }
    }

    /** The backing counter of the gauge for this feed; the gauge is registered once at the first touch. */
    private AtomicInteger failuresGauge(String feedId) {
        return consecutiveFailures.computeIfAbsent(feedId, id -> {
            AtomicInteger counter = new AtomicInteger(0);
            Gauge.builder("atomium.runs.consecutive.failures", counter, AtomicInteger::get)
                    .tag(TAG_FEED, id)
                    .register(registry);
            return counter;
        });
    }

    /** The backing counter of the after-commit-failures gauge for this feed; registered at activation. */
    private AtomicInteger afterCommitFailuresGauge(String feedId) {
        return afterCommitFailures.computeIfAbsent(feedId, id -> {
            AtomicInteger counter = new AtomicInteger(0);
            Gauge.builder("atomium.after.commit.consecutive.failures", counter, AtomicInteger::get)
                    .tag(TAG_FEED, id)
                    .register(registry);
            return counter;
        });
    }

    /**
     * Advance last-success, but only for an activated feed ({@link #feedActivated} seeded it): a commit of a
     * run that is still in flight after a deactivation must not resurrect the removed series.
     */
    private void touchLastSuccess(String feedId) {
        AtomicLong timestamp = lastSuccess.get(feedId);
        if (timestamp != null) {
            timestamp.set(registry.config().clock().wallTime());
        }
    }

    /** The backing timestamp (epoch millis) of the last-success gauge for this feed; registered at activation. */
    private AtomicLong lastSuccessGauge(String feedId) {
        return lastSuccess.computeIfAbsent(feedId, id -> {
            AtomicLong timestamp = new AtomicLong(0);
            TimeGauge.builder("atomium.feed.last.success.time", timestamp, TimeUnit.MILLISECONDS, AtomicLong::get)
                    .tag(TAG_FEED, id)
                    .register(registry);
            return timestamp;
        });
    }

    /** The backing timestamp (epoch millis) of the last-commit gauge for this feed; likewise registered lazily. */
    private AtomicLong lastCommitGauge(String feedId) {
        return lastCommit.computeIfAbsent(feedId, id -> {
            AtomicLong timestamp = new AtomicLong(0);
            TimeGauge.builder("atomium.entries.last.commit.time", timestamp, TimeUnit.MILLISECONDS, AtomicLong::get)
                    .tag(TAG_FEED, id)
                    .register(registry);
            return timestamp;
        });
    }

    /** The backing timestamp (epoch millis) of the last-event gauge for this feed; likewise registered lazily. */
    private AtomicLong lastEventGauge(String feedId) {
        return lastEvent.computeIfAbsent(feedId, id -> {
            AtomicLong timestamp = new AtomicLong(0);
            TimeGauge.builder("atomium.entries.last.event.time", timestamp, TimeUnit.MILLISECONDS, AtomicLong::get)
                    .tag(TAG_FEED, id)
                    .register(registry);
            return timestamp;
        });
    }
}
