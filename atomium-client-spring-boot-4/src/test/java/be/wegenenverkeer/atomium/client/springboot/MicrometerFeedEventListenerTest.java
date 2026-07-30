package be.wegenenverkeer.atomium.client.springboot;

import be.wegenenverkeer.atomium.client.fetch.FeedPointer;
import be.wegenenverkeer.atomium.client.handler.BatchEntry;
import be.wegenenverkeer.atomium.client.handler.FeedRunFailure;
import be.wegenenverkeer.atomium.client.handler.FeedRunResult;
import be.wegenenverkeer.atomium.client.protocol.AtomiumEntry;
import be.wegenenverkeer.atomium.client.protocol.Content;
import io.micrometer.core.instrument.MockClock;
import io.micrometer.core.instrument.simple.SimpleConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test of the metric mapping: feed the listener with feed events and verify the resulting meters in a
 * {@link SimpleMeterRegistry}. The emission of those events by the real pipeline is already covered by
 * {@link FeedConsumerWireMockTest}; here it is purely about the translation event → meter.
 */
class MicrometerFeedEventListenerTest {

    private static final FeedPointer POINTER = new FeedPointer("/0");
    private static final FeedRunResult EMPTY = new FeedRunResult(0, 0, 0);

    private MockClock clock;
    private SimpleMeterRegistry registry;
    private MicrometerFeedEventListener listener;

    @BeforeEach
    void setUp() {
        clock = new MockClock();
        registry = new SimpleMeterRegistry(SimpleConfig.DEFAULT, clock);
        listener = new MicrometerFeedEventListener(registry);
    }

    @Test
    void countsEntriesPerCommit() {
        listener.runStarted("feed-a", POINTER);
        listener.feedPointerAdvanced("feed-a", POINTER, new FeedRunResult(10, 6, 4));
        listener.feedPointerAdvanced("feed-a", POINTER, new FeedRunResult(5, 5, 5));

        assertThat(counter("atomium.entries.read", "feed-a")).isEqualTo(15);
        assertThat(counter("atomium.entries.accepted", "feed-a")).isEqualTo(11);
        assertThat(counter("atomium.entries.processed", "feed-a")).isEqualTo(9);
    }

    @Test
    void keepsTheCountersSeparatePerFeed() {
        listener.feedPointerAdvanced("feed-a", POINTER, new FeedRunResult(10, 10, 10));
        listener.feedPointerAdvanced("feed-b", POINTER, new FeedRunResult(3, 3, 3));

        assertThat(counter("atomium.entries.processed", "feed-a")).isEqualTo(10);
        assertThat(counter("atomium.entries.processed", "feed-b")).isEqualTo(3);
    }

    @Test
    void setsTheLastCommitTimestampOnEveryCommit() {
        assertThat(lastCommit("feed-a")).isNull();   // no commit yet → no gauge yet

        listener.feedPointerAdvanced("feed-a", POINTER, EMPTY);
        double firstCommit = lastCommit("feed-a");
        assertThat(firstCommit).isEqualTo(clock.wallTime());

        clock.add(Duration.ofMinutes(5));
        listener.feedPointerAdvanced("feed-a", POINTER, EMPTY);
        assertThat(lastCommit("feed-a")).isEqualTo(clock.wallTime()).isGreaterThan(firstCommit);
    }

    @Test
    void setsTheLastEventTimestampToTheMostRecentProcessedEntry() {
        assertThat(lastEvent("feed-a")).isNull();   // nothing processed yet → no gauge yet

        OffsetDateTime older = OffsetDateTime.parse("2026-07-28T10:00:00+02:00");
        OffsetDateTime mostRecent = OffsetDateTime.parse("2026-07-28T10:05:00+02:00");
        listener.entriesProcessed("feed-a", List.of(entry("id-1", older), entry("id-2", mostRecent)));

        assertThat(lastEvent("feed-a")).isEqualTo(mostRecent.toInstant().toEpochMilli());

        listener.entriesProcessed("feed-a", List.of());   // an empty flush changes nothing
        assertThat(lastEvent("feed-a")).isEqualTo(mostRecent.toInstant().toEpochMilli());
    }

    @Test
    void countsTheRunsPerOutcome() {
        listener.runCompleted("feed-a", EMPTY);
        listener.runCompleted("feed-a", EMPTY);
        listener.runInterrupted("feed-a", EMPTY);
        listener.runFailed(failure("feed-a", 1));

        assertThat(runs("feed-a", "completed")).isEqualTo(2);
        assertThat(runs("feed-a", "interrupted")).isEqualTo(1);
        assertThat(runs("feed-a", "failed")).isEqualTo(1);
    }

    @Test
    void countsPagesAndNotModifiedPolls() {
        listener.pageFetched("feed-a", null, 3);
        listener.pageFetched("feed-a", null, 0);
        listener.feedNotModified("feed-a");

        assertThat(counter("atomium.pages.fetched", "feed-a")).isEqualTo(2);
        assertThat(counter("atomium.polls.not.modified", "feed-a")).isEqualTo(1);
    }

    @Test
    void theGaugeTracksTheConsecutiveFailuresAndResetsOnASuccessfulRun() {
        assertThat(gauge("feed-a")).isNull();   // no run at all yet → no gauge yet

        listener.runFailed(failure("feed-a", 1));
        assertThat(gauge("feed-a")).isEqualTo(1);

        listener.runFailed(failure("feed-a", 2));
        assertThat(gauge("feed-a")).isEqualTo(2);

        listener.runCompleted("feed-a", EMPTY);   // healthy again
        assertThat(gauge("feed-a")).isZero();
    }

    private double counter(String name, String feedId) {
        return registry.get(name).tag("feed", feedId).counter().count();
    }

    private double runs(String feedId, String outcome) {
        return registry.get("atomium.runs").tags("feed", feedId, "outcome", outcome).counter().count();
    }

    private Double gauge(String feedId) {
        var gauge = registry.find("atomium.runs.consecutive.failures").tag("feed", feedId).gauge();
        return gauge == null ? null : gauge.value();
    }

    private Double lastCommit(String feedId) {
        var gauge = registry.find("atomium.entries.last.commit.time").tag("feed", feedId).timeGauge();
        return gauge == null ? null : gauge.value(TimeUnit.MILLISECONDS);
    }

    private Double lastEvent(String feedId) {
        var gauge = registry.find("atomium.entries.last.event.time").tag("feed", feedId).timeGauge();
        return gauge == null ? null : gauge.value(TimeUnit.MILLISECONDS);
    }

    private static BatchEntry<String> entry(String id, OffsetDateTime updated) {
        return new BatchEntry<>(null, new AtomiumEntry(id, updated, new Content(null, "{}"), List.of()), "content");
    }

    private static FeedRunFailure failure(String feedId, int consecutive) {
        return new FeedRunFailure(feedId, new RuntimeException("boom"), consecutive,
                OffsetDateTime.now(), null, null);
    }
}
