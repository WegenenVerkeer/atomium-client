package be.wegenenverkeer.atomium.client.handler;

import be.wegenenverkeer.atomium.client.protocol.AtomiumEntry;
import be.wegenenverkeer.atomium.client.protocol.FeedPageMetadata;

import java.util.List;

/**
 * The {@link FeedHandler} that processes the feed in <em>batches</em>, in <em>two phases</em>: prepare the
 * batch outside the transaction ({@link #process}), then persist the prepared effect inside the transaction
 * that also advances the feed pointer ({@link #persist}). "Simple" because this is the plain batch layer on
 * top of the processing engine: the framework buffers, carries the intermediate state {@code P} between the
 * two phases and decides when a batch is wrapped up — the handler bean stays stateless.
 *
 * <p>Choose this over {@link EntryFeedHandler} when processing per entry is too expensive: a feed that
 * produces events in bursts, or processing that involves remote lookups you want to do for many entries in
 * one call. The classic shape: an asset repository system publishes change events; {@code process} collects
 * the distinct asset ids of the batch and looks them up in one call against an API that accepts at most
 * 1000 ids per request; {@code persist} writes the results locally.
 *
 * <p>The framework buffers accepted entries up to the feed's {@code preferredProcessingSize} and then calls
 * {@code process(entries)} → holds {@code P} → opens the transaction → {@code persist(P)} + feed pointer,
 * atomically. A partial batch is wrapped up the same way at the end of the feed, on a clean interruption and
 * when the safety net ({@code maxUncommittedPages}) fires. If {@code process} or {@code persist} throws, the
 * run fails: rollback, the pointer stays put, and everything is offered again on the next run.
 *
 * @param <C> the domain type of the entry content
 * @param <P> the prepared intermediate state that {@code process} hands to {@code persist}
 */
public interface SimpleBatchedProcessingFeedHandler<C, P> extends FeedHandler<C> {

    /**
     * Is this entry relevant to this handler? {@code false} → the framework ignores it entirely (it does not
     * enter the batch and does not count toward the processing threshold) and simply advances the feed
     * pointer over it. Operates on the <em>decoded</em> content. Default: everything is relevant.
     */
    default boolean accepts(FeedPageMetadata pageMetadata, AtomiumEntry entry, C content) {
        return true;
    }

    /**
     * Phase 1, <em>outside</em> any transaction: prepare the batch — collect, deduplicate, look things up,
     * build the effect to persist. This phase may be slow (remote calls are fine here) and may be repeated
     * after a crash, so keep it free of non-idempotent side effects: reads and idempotent calls are fine,
     * everything else belongs in {@link #persist}.
     *
     * @param entries the accepted entries of this batch, in read order (never empty)
     * @return the prepared intermediate state, plus optionally the processed count
     *         (see {@link ProcessResult})
     */
    ProcessResult<P> process(List<ProcessingEntry<C>> entries);

    /**
     * Phase 2, <em>inside</em> the transaction that also advances the feed pointer: persist the prepared
     * effect. Keep it fast and local — no network calls here. If this throws, the transaction rolls back and
     * the whole batch is offered again on the next run.
     *
     * @param prepared the intermediate state returned by {@link #process}
     */
    void persist(P prepared);
}
