package be.wegenenverkeer.atomium.client.handler;

import be.wegenenverkeer.atomium.client.protocol.AtomiumEntry;
import be.wegenenverkeer.atomium.client.protocol.FeedPageMetadata;

import java.util.List;

/**
 * The <em>accumulator</em> of a {@link BatchedFeedHandler}: it collects the entries of one batch and decides for
 * itself when that batch is complete.
 *
 * <p>This is where the mutable state of batching lives — not in the handler bean. The framework asks for a fresh
 * instance per batch ({@link BatchedFeedHandler#startBatch(int)}) and discards it after the flush, which keeps the
 * bean itself <em>stateless</em> (and thus a regular singleton {@code @Component}).
 *
 * <p>{@link #isComplete()} is <b>the</b> extension point: that is where you define your own policy (count, time, a
 * domain criterion, …). {@link DefaultFeedHandlerBatch} covers the common case (dedup + threshold on the number of
 * distinct keys).
 *
 * <p>A custom implementation may do more than pile up entries: it may build domain aggregates along the way and offer
 * them to {@link BatchedFeedHandler#onBatch} — after all, that receives the batch itself, not just {@link #getBuffer()}.
 *
 * <p>All methods run on the feed thread (single-threaded); no synchronization needed.
 *
 * @param <C> the domain type of the entry content
 */
public interface FeedHandlerBatch<C> {

    /** Take an (accepted) entry into the batch. */
    void onEntry(FeedPageMetadata pageMetadata, AtomiumEntry entry, C content);

    /**
     * Is the batch ready to be processed? The framework then flushes as soon as possible — i.e. within the transaction
     * that also advances the feed pointer. A batch that never lets this become {@code true} is flushed anyway
     * at the end of the feed (or on an interruption).
     */
    boolean isComplete();

    /** Does the batch contain nothing (yet)? Then there is nothing to process. */
    boolean isEmpty();

    /** The entries to process, in processing order. */
    List<BatchEntry<C>> getBuffer();
}
