package be.wegenenverkeer.atomium.client.handler;

import be.wegenenverkeer.atomium.client.protocol.FeedPageMetadata;

import java.util.List;

/**
 * The (only) {@link FeedHandlerController}: it keeps the running {@link FeedHandlerBatch} and translates its
 * state into a {@link Code} for the {@link FeedConsumerImpl}. An {@link EntryFeedHandler} also passes through here,
 * via the {@link EntryFeedHandlerAdapter} (a batch of 1) — "process per entry" is simply the special case.
 *
 * <p>Three things happen here, and nowhere else: {@link FeedHandler#accepts} is applied <em>before</em> the entry
 * lands in the batch (a filtered-out entry therefore does not count towards the threshold), a fresh batch is started
 * after every flush, and the <b>page safety net</b> guards the uncommitted window (see {@link #onEndOfPage}).
 *
 * @param <C> the domain type of the entry content
 */
class BatchedFeedHandlerController<C> implements FeedHandlerController<C> {

    private final BatchedFeedHandler<C> handler;
    private final int preferredBatchSize;
    private final int maxUnflushedPages;

    private FeedHandlerBatch<C> batch;
    private int pagesSinceFlush;

    // the counters of the run; the controller is the only one that can know all three (it applies accepts and sees the dedup)
    private int read;
    private int accepted;
    private int processed;

    BatchedFeedHandlerController(BatchedFeedHandler<C> handler, int preferredBatchSize, int maxUnflushedPages) {
        this.handler = handler;
        this.preferredBatchSize = preferredBatchSize;
        this.maxUnflushedPages = maxUnflushedPages;
        this.batch = handler.startBatch(preferredBatchSize);
    }

    @Override
    public Code onEntry(BatchEntry<C> entry) {
        read++;
        if (!handler.accepts(entry.pageMetadata(), entry.entry(), entry.content())) {
            // irrelevant: it does not count towards the threshold. If the batch is empty, the consumer may (on a
            // boundary) checkpoint past it — otherwise a long irrelevant tail would be fetched again on every poll.
            return keepBuffering();
        }
        accepted++;
        batch.onEntry(entry.pageMetadata(), entry.entry(), entry.content());
        return batch.isComplete() ? Code.BUFFER_COMPLETE : Code.BUFFERING;
    }

    /**
     * A batch may span page boundaries; as long as it is not complete, the pointer stays pinned.
     *
     * <p>That does carry a danger, and this is the safety net against it. A feed that filters or deduplicates heavily
     * rarely reaches its threshold: the batch stays half full, the pointer does not advance, and the window a crash
     * would have to re-read grows without bound (pathologically the run never reaches the head → never a flush). That
     * is why we force a flush once we are {@code maxUnflushedPages} pages in without one: better to process half a
     * batch than to have to fetch an unbounded number of pages again.
     */
    @Override
    public Code onEndOfPage(FeedPageMetadata pageMetadata) {
        if (batch.isEmpty()) {
            // the consumer checkpoints its pointer here → there is nothing to re-read, so the window is zero
            pagesSinceFlush = 0;
            return Code.BUFFER_EMPTY;
        }
        pagesSinceFlush++;
        return pagesSinceFlush >= maxUnflushedPages ? Code.BUFFER_COMPLETE : Code.BUFFERING;
    }

    /** End of feed: whatever is still in the batch gets processed — a batch does not survive polls. */
    @Override
    public Code onEndOfFeed() {
        return flushTheRest();
    }

    /** Cleanly interrupted: we still process the partial batch (instead of discarding it), so the work is not lost. */
    @Override
    public Code onInterrupted() {
        return flushTheRest();
    }

    /**
     * The <em>processed</em> entries (post-{@code accepts}, post-dedup) are only returned here, not emitted as an
     * event: we run inside the flush transaction, and only the consumer knows whether that commit succeeds.
     */
    @Override
    public List<BatchEntry<C>> flush() {
        List<BatchEntry<C>> toProcess = batch.getBuffer();
        handler.onBatch(batch);
        processed += toProcess.size();
        batch = handler.startBatch(preferredBatchSize);
        pagesSinceFlush = 0;
        return toProcess;
    }

    @Override
    public FeedRunResult result() {
        return new FeedRunResult(read, accepted, processed);
    }

    /** In the middle of the feed: only the batch itself ({@link FeedHandlerBatch#isComplete()}) decides when to flush. */
    private Code keepBuffering() {
        return batch.isEmpty() ? Code.BUFFER_EMPTY : Code.BUFFERING;
    }

    /** At the end of the run: whatever is still in there must be processed, however incomplete the batch. */
    private Code flushTheRest() {
        return batch.isEmpty() ? Code.BUFFER_EMPTY : Code.BUFFER_COMPLETE;
    }
}
