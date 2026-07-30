package be.wegenenverkeer.atomium.client.handler;

import be.wegenenverkeer.atomium.client.protocol.FeedPageMetadata;

import java.util.List;

/**
 * <b>Framework-internal; not a developer SPI.</b> The layer between the {@link FeedConsumerImpl} and the
 * {@link FeedHandler}. The controller owns the (mutable) <em>buffer</em> — which keeps the handler bean stateless —
 * and translates the read-loop events into a {@link Code buffer state}. Based on that code the consumer decides what
 * happens to the transaction and the feedPointer; the controller knows nothing about transactions or persistence.
 *
 * <p>One fresh instance per feed, per run; all callbacks run on the feed thread (single-threaded, no
 * synchronization needed).
 *
 * @param <C> the domain type of the entry content
 */
interface FeedHandlerController<C> {

    /**
     * The state of the buffer after a callback — and with it what the consumer has to do.
     */
    enum Code {

        /** Buffer non-empty but not complete yet. Consumer: keep reading, persist nothing (pointer pinned). */
        BUFFERING,

        /**
         * Buffer ready to be processed (threshold reached or end of feed; not necessarily at max capacity).
         * Consumer: {@code tx { flush(); persist the pointer; }} — batch effect and pointer atomically.
         */
        BUFFER_COMPLETE,

        /**
         * Buffer empty (just flushed, or everything filtered out). Consumer: checkpoint the pointer only on a
         * page/feed boundary; deliberately do nothing in the middle of a page's entries.
         */
        BUFFER_EMPTY
    }

    /** A (decoded) entry is offered. */
    Code onEntry(BatchEntry<C> entry);

    /** All entries of a page have been offered. */
    Code onEndOfPage(FeedPageMetadata pageMetadata);

    /** The head of the feed has been reached (no younger events at this moment). */
    Code onEndOfFeed();

    /** The run is being cleanly interrupted (deactivation/shutdown). */
    Code onInterrupted();

    /**
     * Offer the buffered entries to the handler and empty the buffer. Called <em>only</em> by the consumer
     * after a {@link Code#BUFFER_COMPLETE}, and then within the same transaction in which the feedPointer is
     * advanced. If the handler throws, that transaction rolls back and the run fails (the pointer does not
     * advance → the entries come around again on the next run).
     *
     * @return the entries that were actually delivered to the handler (after {@code accepts} and after dedup). The
     *         controller <em>reports</em> this and emits no events itself: it runs here within an open transaction,
     *         and only the consumer knows whether the commit succeeds. It emits {@link FeedEventListener#entriesProcessed}
     *         afterwards.
     */
    List<BatchEntry<C>> flush();

    /** The counters of this run so far (read / accepted / processed). */
    FeedRunResult result();
}
