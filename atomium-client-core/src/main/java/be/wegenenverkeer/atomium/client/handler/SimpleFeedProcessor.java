package be.wegenenverkeer.atomium.client.handler;

import be.wegenenverkeer.atomium.client.fetch.FeedPointer;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The {@link FeedProcessor} of a {@link SimpleProcessingFeedHandler}: buffers accepted entries up to
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
class SimpleFeedProcessor<C, P> implements FeedProcessor<C> {

    private final SimpleProcessingFeedHandler<C, P> handler;
    private final int maxProcessingSize;

    private List<ProcessingEntry<C>> buffer = new ArrayList<>();
    // set by process, persisted by persist, reported and cleared by afterCommit — the phases are disjoint
    // in time because the consumer calls afterCommit immediately after every commit
    private @Nullable Batch<C, P> batch;
    private int accepted;
    private int processed;

    SimpleFeedProcessor(SimpleProcessingFeedHandler<C, P> handler, int maxProcessingSize) {
        this.handler = handler;
        this.maxProcessingSize = maxProcessingSize;
    }

    @Override
    public State processEntry(ProcessingEntry<C> entry) {
        if (!handler.accepts(entry.pageMetadata(), entry.entry(), entry.content())) {
            return state();
        }
        accepted++;
        buffer.add(entry);
        if (buffer.size() < maxProcessingSize) {
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
        List<ProcessingEntry<C>> entries = List.copyOf(buffer);
        ProcessResult<P> result = handler.process(entries);
        Integer reported = result.processed();
        batch = new Batch<>(entries, result, reported != null ? reported : entries.size());
        return State.READY;
    }

    /**
     * Phase 2: persist the prepared effect; afterwards the processor is empty again for the next batch. The
     * persisted batch is kept for the post-commit hook ({@link #afterCommit}).
     */
    @Override
    public void persist() {
        if (batch == null) {
            throw new IllegalStateException("persist() called without a processed batch");
        }
        handler.persist(batch.result().value());
        processed += batch.processed();
        buffer = new ArrayList<>();
    }

    /**
     * The handler's post-commit hook, on every commit: with the batch when this commit persisted one
     * (the batch travels with exactly its own commit), with an empty batch on a pointer-only checkpoint.
     * Cleared before the handler runs, so a throwing hook cannot get the batch re-reported at the next commit.
     */
    @Override
    public boolean afterCommit(FeedPointer persistedPointer) {
        Batch<C, P> committed = batch;
        batch = null;
        handler.afterCommit(persistedPointer,
                committed == null ? List.of() : committed.entries(),
                committed == null ? null : committed.result());
        return true;
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

    private record Batch<C, P>(List<ProcessingEntry<C>> entries, ProcessResult<P> result, int processed) {
    }
}
