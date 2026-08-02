package be.wegenenverkeer.atomium.client.handler;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The {@link FeedProcessor} of a {@link SimpleBatchedProcessingFeedHandler}: buffers accepted entries up to
 * the processing threshold and then runs phase 1 ({@code process}, in the seam callback — outside any
 * transaction); the prepared {@code P} is held until the consumer opens the transaction and {@link #persist()}
 * runs phase 2. A partial batch is wrapped up the same way on {@link CheckpointReason#WINDOW_EXHAUSTED},
 * {@link CheckpointReason#END_OF_FEED}, {@link CheckpointReason#INTERRUPTED} and
 * {@link CheckpointReason#READ_FAILURE}; on a plain {@link CheckpointReason#PAGE_BOUNDARY} it keeps
 * buffering (a batch may span pages — the pointer stays pinned).
 *
 * @param <C> the domain type of the entry content
 * @param <P> the prepared intermediate state of the handler
 */
class SimpleBatchedFeedProcessor<C, P> implements FeedProcessor<C> {

    private final SimpleBatchedProcessingFeedHandler<C, P> handler;
    private final int preferredProcessingSize;

    private List<ProcessingEntry<C>> buffer = new ArrayList<>();
    private @Nullable Prepared<P> prepared;
    private int accepted;
    private int processed;

    SimpleBatchedFeedProcessor(SimpleBatchedProcessingFeedHandler<C, P> handler, int preferredProcessingSize) {
        this.handler = handler;
        this.preferredProcessingSize = preferredProcessingSize;
    }

    @Override
    public State processEntry(ProcessingEntry<C> entry) {
        if (!handler.accepts(entry.pageMetadata(), entry.entry(), entry.content())) {
            return state();
        }
        accepted++;
        buffer.add(entry);
        if (buffer.size() < preferredProcessingSize) {
            return State.BUFFERING;
        }
        return process();
    }

    @Override
    public State onCheckpointOpportunity(CheckpointReason reason) {
        if (buffer.isEmpty()) {
            return State.IDLE;
        }
        return switch (reason) {
            // a batch may span pages; only the safety net and the end of the run wrap up a partial batch
            case PAGE_BOUNDARY -> State.BUFFERING;
            case WINDOW_EXHAUSTED, END_OF_FEED, INTERRUPTED, READ_FAILURE -> process();
        };
    }

    /** Phase 1: hand the batch to the handler; the prepared state waits for {@link #persist()}. */
    private State process() {
        List<ProcessingEntry<C>> batch = List.copyOf(buffer);
        ProcessResult<P> result = handler.process(batch);
        Integer reported = result.processed();
        prepared = new Prepared<>(result.value(), reported != null ? reported : batch.size());
        return State.READY;
    }

    /** Phase 2: persist the prepared effect; afterwards the processor is empty again for the next batch. */
    @Override
    public void persist() {
        Prepared<P> toPersist = prepared;
        if (toPersist == null) {
            throw new IllegalStateException("persist() called without a processed batch");
        }
        handler.persist(toPersist.value());
        processed += toPersist.processed();
        prepared = null;
        buffer = new ArrayList<>();
    }

    @Override
    public int accepted() {
        return accepted;
    }

    @Override
    public int processed() {
        return processed;
    }

    private State state() {
        return buffer.isEmpty() ? State.IDLE : State.BUFFERING;
    }

    private record Prepared<P>(P value, int processed) {
    }
}
