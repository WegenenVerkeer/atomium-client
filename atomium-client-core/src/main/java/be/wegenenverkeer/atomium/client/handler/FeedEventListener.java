package be.wegenenverkeer.atomium.client.handler;

import be.wegenenverkeer.atomium.client.fetch.FeedPointer;
import be.wegenenverkeer.atomium.client.protocol.FeedPageMetadata;

import java.util.List;

/**
 * Observability SPI: one point where the feed processing reports its events. Metrics, health, logging
 * and alerting are all consumers of the same events, so one emission point keeps the consumer clean (no
 * Micrometer/… in the processing) and makes such extensions a matter of "write a listener".
 *
 * <p>All methods have an empty default — implement only what you need. Add implementations per feed
 * via {@link Feed.Builder#addListener}.
 *
 * <p><b>Deliberately not typed on the content {@code C}.</b> This SPI is <em>cross-cutting</em> (metrics, health,
 * logging, alerting) and that layer does not need the domain type: {@link #entriesProcessed} delivers
 * {@code BatchEntry<?>}s from which you take id/updated/page/counts, not the content. Anyone who wants to process
 * the <em>content</em> is doing domain work — and that belongs in the handler ({@link EntryFeedHandler#onEntry} /
 * {@link BatchedFeedHandler#onBatch}), where {@code C} <em>is</em> typed. A generic parameter here would moreover
 * gain nothing: a listener often applies app-wide to <em>all</em> feeds, each with a different {@code C}.
 *
 * <p><b>Contract:</b> the callbacks run on the feed thread, always <em>after</em> the commit point they belong to —
 * never inside an open transaction. {@link #entriesProcessed} and {@link #feedPointerAdvanced} therefore only fire
 * once the commit has succeeded: what you see here is what a crash at that moment would leave behind. A listener that
 * throws an exception does <em>not</em> break the run: the failure is logged (WARN) and ignored. Keep implementations
 * light and non-blocking.
 */
public interface FeedEventListener {

    /**
     * A run has started; {@code startPosition} is the pointer from which reading happens.
     *
     * <p><b>Edge case:</b> for a brand-new feed that start position is determined lazily (an HTTP call to the source,
     * see {@code initial-feed-pointer}). If <em>that</em> fails, there is no start position and only {@link #runFailed}
     * follows — without {@code runStarted}. Once a pointer has ever been persisted, this can no longer happen.
     */
    default void runStarted(String feedId, FeedPointer startPosition) {
    }

    /** A page has been fetched at the source. {@code entryCount} = what it delivered (may be 0). */
    default void pageFetched(String feedId, FeedPageMetadata pageMetadata, int entryCount) {
    }

    /**
     * The source replied {@code 304 Not Modified}: there is nothing new since the previous poll. Distinct from
     * {@link #endOfFeedReached} — there we <em>did</em> fetch the head.
     */
    default void feedNotModified(String feedId) {
    }

    /**
     * Entries have been offered to the handler <em>and committed</em>. These are the truly processed entries: after
     * {@link FeedHandler#accepts} and after the batch's dedup. With an {@link EntryFeedHandler} that is one entry at
     * a time; with a {@link BatchedFeedHandler} the entire flushed batch. The counter for metrics.
     */
    default void entriesProcessed(String feedId, List<? extends BatchEntry<?>> entries) {
    }

    /**
     * The feed pointer has advanced and been committed — the recovery point after a crash. Fires on every commit: both
     * after a flush and on a checkpoint on a boundary (e.g. past a page of which everything was filtered out).
     * {@code sincePreviousCommit} carries the counters of what was added since the previous commit, so that a
     * metrics listener can add up per commit — even in the middle of a long run, and without loss if the run
     * still fails afterwards.
     */
    default void feedPointerAdvanced(String feedId, FeedPointer feedPointer, FeedRunResult sincePreviousCommit) {
    }

    /** A page has been fully traversed. Note: that does not necessarily mean a commit happened — a batch may span
     * page boundaries. */
    default void pageProcessed(String feedId, FeedPageMetadata pageMetadata) {
    }

    /** The consumer has reached the head of the feed (no younger events at this moment). */
    default void endOfFeedReached(String feedId) {
    }

    /** The run was cleanly interrupted (deactivation/shutdown) after a commit point; the next run resumes the rest. */
    default void runInterrupted(String feedId, FeedRunResult result) {
    }

    /** The run ended normally. */
    default void runCompleted(String feedId, FeedRunResult result) {
    }

    /**
     * The run failed. Emitted by the {@link FeedRunner} (not by the consumer): it knows the backoff. The
     * {@link FeedRunFailure} carries the counter ({@code consecutiveFailures}), the backoff deadline
     * ({@code nextAttemptAfter}) and — where applicable — the entry context (entry id + phase), so that an
     * alerting listener can decide for itself when to raise the alarm.
     */
    default void runFailed(FeedRunFailure failure) {
    }
}
