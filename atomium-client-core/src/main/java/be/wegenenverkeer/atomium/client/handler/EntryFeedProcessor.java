package be.wegenenverkeer.atomium.client.handler;

import org.jspecify.annotations.Nullable;

/**
 * The {@link FeedProcessor} of an {@link EntryFeedHandler}: every accepted entry is immediately
 * {@link State#READY}, and {@link #persist()} delivers it to {@link EntryFeedHandler#onEntry} — one entry per
 * transaction, exactly the behavior that handler promises. There is never uncommitted state at a boundary, so
 * every checkpoint opportunity finds this processor {@link State#IDLE}.
 *
 * @param <C> the domain type of the entry content
 */
class EntryFeedProcessor<C> implements FeedProcessor<C> {

    private final String feedId;
    private final EntryFeedHandler<C> handler;

    private @Nullable ProcessingEntry<C> current;
    private int accepted;
    private int processed;

    EntryFeedProcessor(String feedId, EntryFeedHandler<C> handler) {
        this.feedId = feedId;
        this.handler = handler;
    }

    @Override
    public State processEntry(ProcessingEntry<C> entry) {
        if (!handler.accepts(entry.pageMetadata(), entry.entry(), entry.content())) {
            return State.IDLE;
        }
        accepted++;
        current = entry;
        return State.READY;
    }

    @Override
    public State onCheckpointOpportunity(CheckpointReason reason) {
        return State.IDLE;
    }

    /**
     * The handler callback, wrapped with the entry context, so that on a failure the run failure still
     * carries which entry and which phase failed.
     */
    @Override
    public void persist() {
        ProcessingEntry<C> entry = current;
        if (entry == null) {
            throw new IllegalStateException("persist() called without a ready entry");
        }
        try {
            handler.onEntry(entry.pageMetadata(), entry.entry(), entry.content());
        } catch (RuntimeException e) {
            throw new FeedEntryProcessingException(feedId, entry.entry().id(), FeedEntryPhase.HANDLER, e);
        }
        processed++;
        current = null;
    }

    @Override
    public int accepted() {
        return accepted;
    }

    @Override
    public int processed() {
        return processed;
    }
}
