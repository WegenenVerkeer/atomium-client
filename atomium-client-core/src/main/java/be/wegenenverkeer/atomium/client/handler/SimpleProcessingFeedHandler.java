package be.wegenenverkeer.atomium.client.handler;

import be.wegenenverkeer.atomium.client.fetch.FeedPointer;
import be.wegenenverkeer.atomium.client.protocol.AtomiumEntry;
import be.wegenenverkeer.atomium.client.protocol.FeedPageMetadata;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * The {@link FeedHandler} that processes the feed in <em>batches</em>, in <em>two phases</em>: prepare the
 * batch outside the transaction ({@link #process}), then persist the prepared effect inside the transaction
 * that also advances the feed pointer ({@link #persist}). "Simple" because this is the simple use of the
 * processing engine: the framework buffers, carries the intermediate state {@code P} between the two phases
 * and decides when a batch is wrapped up — the handler bean stays stateless.
 *
 * <p>Choose this over {@link EntryFeedHandler} whenever processing involves <em>remote</em> work — also
 * when every event concerns just a single entity. The classic shape: an asset repository system publishes
 * "asset X changed" events; {@code process} collects the distinct asset ids of the batch and looks them up
 * against the source (in one call if the API takes many ids, otherwise a loop of calls — either way outside
 * the transaction); {@code persist} writes the results locally. Batching then pays three times over: the
 * transaction never waits on remote I/O (a transaction stalled on a slow remote system holds its database
 * connection hostage exactly when that system is having trouble), repeated ids within a burst collapse into
 * one lookup, and — because {@code persist} reads and writes back-to-back — any optimistic-locking window
 * stays minimal by construction instead of spanning the remote call.
 *
 * <p>The framework buffers accepted entries and calls {@code process(entries)} → holds {@code P} → opens the
 * transaction → {@code persist(P)} + feed pointer, atomically. If {@code process} or {@code persist} throws,
 * the run fails: rollback, the pointer stays put, and everything is offered again on the next run.
 *
 * <p><b>The batch size varies.</b> {@code maxProcessingSize} accepted entries is the <em>maximum</em>, not a
 * guarantee: a batch is wrapped up by whichever comes first — the maximum is reached, the safety net fires
 * ({@code maxUncommittedPages} pages read without a commit), the end of the feed is reached, the run is
 * cleanly interrupted, or reading the next entry fails. An implementation of {@code process} must therefore
 * cope with any batch size from 1 up to the maximum — never assume a full batch.
 *
 * @param <C> the domain type of the entry content
 * @param <P> the prepared intermediate state that {@code process} hands to {@code persist}
 */
public interface SimpleProcessingFeedHandler<C, P> extends FeedHandler<C> {

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
     * @param entries the <em>accepted</em> entries of this batch (entries {@link #accepts} rejected are not
     *                in here and never counted), in read order. Never empty, at most {@code maxProcessingSize};
     *                the actual size varies per batch — see the class javadoc for what wraps up a batch
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

    /**
     * Post-commit hook, on the feed thread, <em>outside</em> any transaction, after <em>every</em> durable
     * commit of the feed pointer (and after the {@code feedPointerAdvanced} listeners). The place for domain
     * side effects that follow committed progress, such as reporting the processed position to the source
     * system. The hook decides for itself what interests it: every pointer commit, or only commits that
     * persisted a batch ({@code entries} non-empty) — note that the committed pointer may sit past the last
     * processed entry (a checkpoint also advances it over entries {@link #accepts} rejected).
     *
     * <p><b>Best effort</b>: running after the transaction means, trivially, no transactional guarantee — a
     * crash between the commit and this hook skips the call, and the next commit simply reports the then
     * current state. An effect that must not get lost belongs in {@link #persist} instead, inside the
     * transaction. <b>A failing hook does not break the run</b>; its outcome is reported through
     * {@link FeedEventListener#afterCommitCompleted} — the signal to alert on when the effect must not
     * silently stall.
     *
     * @param persistedPointer the feed pointer this commit persisted
     * @param entries          the entries of the batch this commit persisted, as handed to {@link #process};
     *                         empty for a pointer-only checkpoint (an empty or entirely filtered-out stretch)
     * @param processResult    what {@link #process} returned for that batch; {@code null} for a pointer-only
     *                         checkpoint
     */
    default void afterCommit(FeedPointer persistedPointer, List<ProcessingEntry<C>> entries,
                             @Nullable ProcessResult<P> processResult) {
    }
}
