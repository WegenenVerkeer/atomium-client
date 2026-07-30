package be.wegenenverkeer.atomium.client.handler;

import be.wegenenverkeer.atomium.client.protocol.AtomiumEntry;
import be.wegenenverkeer.atomium.client.protocol.FeedPageMetadata;

import java.util.ArrayList;
import java.util.List;

/**
 * Adapts an {@link EntryFeedHandler} to a {@link BatchedFeedHandler} with <em>batch size 1</em>. As a result,
 * only one controller exists in the framework ({@link BatchedFeedHandlerController}): "process per entry" is
 * simply the special case "batch of one, without dedup".
 *
 * <p>Every entry immediately makes the batch complete, so the consumer flushes and commits per entry — exactly
 * the one-entry-per-transaction behavior an {@link EntryFeedHandler} promises.
 *
 * @param <C> the domain type of the entry content
 */
class EntryFeedHandlerAdapter<C> implements BatchedFeedHandler<C> {

    private final String feedId;
    private final EntryFeedHandler<C> handler;

    EntryFeedHandlerAdapter(String feedId, EntryFeedHandler<C> handler) {
        this.feedId = feedId;
        this.handler = handler;
    }

    @Override
    public String getFeedId() {
        return handler.getFeedId();
    }

    @Override
    public boolean accepts(FeedPageMetadata pageMetadata, AtomiumEntry entry, C content) {
        return handler.accepts(pageMetadata, entry, content);
    }

    @Override
    public void pushEntry(C content) {
        handler.pushEntry(content);
    }

    /** The threshold here is 1 by definition; a configured batch size does not apply to an
     * {@link EntryFeedHandler} (the assembly layer rejects it at startup). */
    @Override
    public FeedHandlerBatch<C> startBatch(int preferredBatchSize) {
        return new SingleEntryBatch<>();
    }

    /**
     * The batch contains exactly one by definition. We wrap with the entry context, so that on a failure the
     * {@link FeedRunner} still knows which entry and which phase failed (with a real batch there is no single culprit).
     */
    @Override
    public void onBatch(FeedHandlerBatch<C> batch) {
        for (BatchEntry<C> batchEntry : batch.getBuffer()) {
            try {
                handler.onEntry(batchEntry.pageMetadata(), batchEntry.entry(), batchEntry.content());
            } catch (RuntimeException e) {
                throw new FeedEntryProcessingException(feedId, batchEntry.entry().id(), FeedEntryPhase.HANDLER, e);
            }
        }
    }

    /** A batch that is complete after one entry and does not deduplicate (with threshold 1 there is nothing to deduplicate). */
    private static final class SingleEntryBatch<C> implements FeedHandlerBatch<C> {

        private final List<BatchEntry<C>> buffer = new ArrayList<>(1);

        @Override
        public void onEntry(FeedPageMetadata pageMetadata, AtomiumEntry entry, C content) {
            buffer.add(new BatchEntry<>(pageMetadata, entry, content));
        }

        @Override
        public boolean isComplete() {
            return !buffer.isEmpty();
        }

        @Override
        public boolean isEmpty() {
            return buffer.isEmpty();
        }

        @Override
        public List<BatchEntry<C>> getBuffer() {
            return List.copyOf(buffer);
        }
    }
}
