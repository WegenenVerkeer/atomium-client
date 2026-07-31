package be.wegenenverkeer.atomium.client.handler;

import be.wegenenverkeer.atomium.client.fetch.EventCoordinate;
import be.wegenenverkeer.atomium.client.fetch.FeedPointer;
import be.wegenenverkeer.atomium.client.fetch.FetchCoordinate;
import be.wegenenverkeer.atomium.client.protocol.FeedPageMetadata;
import be.wegenenverkeer.atomium.client.protocol.FeedPageRel;
import org.jspecify.annotations.Nullable;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test {@link FeedEventListener} that records every event in order, so a test can validate the exact event
 * sequence. Thread-safe ({@link CopyOnWriteArrayList}): the feed thread writes while the test thread reads concurrently.
 *
 * <p>Because {@code feedPointerAdvanced} fires on every commit (and guaranteed only after the commit), this listener
 * doubles as the window on the pointer bookkeeping: {@link #pointerCommits()} gives exactly the positions at which the
 * feed committed — i.e. what a crash at any moment would leave behind. That a pointer did <em>not</em> advance (a
 * processor that is still buffering) shows in the <em>absence</em> of a commit.
 */
public class RecordingFeedEventListener implements FeedEventListener {

    private final List<String> events = new CopyOnWriteArrayList<>();
    private final List<String> pointerCommits = new CopyOnWriteArrayList<>();
    private final List<String> commitDeltas = new CopyOnWriteArrayList<>();
    private final List<String> commitLatestEvents = new CopyOnWriteArrayList<>();
    private final List<FeedRunFailure> failures = new CopyOnWriteArrayList<>();

    @Override
    public void runStarted(String feedId, FeedPointer startPosition) {
        events.add("runStarted");
    }

    @Override
    public void pageFetched(String feedId, FeedPageMetadata pageMetadata, int entryCount) {
        events.add("pageFetched(%s, %d)".formatted(page(pageMetadata), entryCount));
    }

    @Override
    public void feedNotModified(String feedId) {
        events.add("feedNotModified");
    }

    @Override
    public void feedPointerAdvanced(String feedId, FeedPointer feedPointer, FeedRunResult sincePreviousCommit,
                                    @Nullable OffsetDateTime latestEventUpdated) {
        String position = show(feedPointer);
        events.add("feedPointerAdvanced(%s)".formatted(position));
        pointerCommits.add(position);
        commitDeltas.add(show(sincePreviousCommit));
        commitLatestEvents.add(latestEventUpdated == null ? "-" : latestEventUpdated.toString());
    }

    @Override
    public void pageProcessed(String feedId, FeedPageMetadata pageMetadata) {
        events.add("pageProcessed(%s)".formatted(page(pageMetadata)));
    }

    @Override
    public void endOfFeedReached(String feedId) {
        events.add("endOfFeedReached");
    }

    @Override
    public void runInterrupted(String feedId, FeedRunResult result) {
        events.add("runInterrupted(%s)".formatted(show(result)));
    }

    @Override
    public void runCompleted(String feedId, FeedRunResult result) {
        events.add("runCompleted(%s)".formatted(show(result)));
    }

    @Override
    public void runFailed(FeedRunFailure failure) {
        events.add("runFailed(%d)".formatted(failure.consecutiveFailures()));
        failures.add(failure);
    }

    /** The full event timeline. */
    public List<String> events() {
        return events;
    }

    /** Only the positions at which the feed pointer was committed, in order. */
    public List<String> pointerCommits() {
        return pointerCommits;
    }

    /** The {@code sincePreviousCommit} delta per commit, in commit order. */
    public List<String> commitDeltas() {
        return commitDeltas;
    }

    /** The {@code latestEventUpdated} per commit ({@code -} when the commit covered no entries), in commit order. */
    public List<String> commitLatestEvents() {
        return commitLatestEvents;
    }

    /** The full {@link FeedRunFailure} payloads, for asserts on the entry context of a failure. */
    public List<FeedRunFailure> failures() {
        return failures;
    }

    public void reset() {
        events.clear();
        pointerCommits.clear();
        commitDeltas.clear();
        commitLatestEvents.clear();
        failures.clear();
    }

    /** Compact representation: {@code lastEvent=/0#id-001 nextFetch=/0?after=id-001}. */
    private static String show(FeedPointer feedPointer) {
        EventCoordinate lastEvent = feedPointer.lastEvent();
        FetchCoordinate nextFetch = feedPointer.nextFetch();
        String filter = nextFetch.filterEventId() == null ? "" : "?after=" + nextFetch.filterEventId();
        return "lastEvent=%s nextFetch=%s%s".formatted(
                lastEvent == null ? "-" : lastEvent.pageLink() + "#" + lastEvent.eventId(),
                nextFetch.pageLink(), filter);
    }

    private static String show(FeedRunResult result) {
        return "read=%d, accepted=%d, processed=%d"
                .formatted(result.read(), result.accepted(), result.processed());
    }

    private static String page(FeedPageMetadata pageMetadata) {
        return pageMetadata.href(FeedPageRel.SELF);
    }
}
