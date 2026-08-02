package be.wegenenverkeer.atomium.client.handler;

// Maintainer note — this seam is deliberately package-private. Publishing an internal is additive and
// non-breaking, un-publishing is breaking: so none of the growth path below gets built (or published) before
// a real user exists. What we anticipate:
//
// 1. A handler that provides its own FeedProcessor, for full control over the processing:
//
//        public interface ProcessingFeedHandler<C> extends FeedHandler<C> {
//            FeedProcessor<C> feedProcessor(int maxProcessingSize);
//        }
//
//    SimpleProcessingFeedHandler is then no more than the simple use case of that SPI:
//
//        public interface SimpleProcessingFeedHandler<C, P> extends ProcessingFeedHandler<C> {
//            default FeedProcessor<C> feedProcessor(int maxProcessingSize) {
//                return new SimpleFeedProcessor<>(this, maxProcessingSize);
//            }
//            // accepts / process / persist as today
//        }
//
//    Named as the factory it is ("give me your FeedProcessor") — when it is called is the framework's
//    business, so its javadoc must state the contract the name no longer hints at: called at the start of
//    every run, and it must return a fresh instance each time. This is why the public tier is called
//    *Simple*ProcessingFeedHandler, and why FeedRuntime keeps the handler→processor switch in one place.
//
// 2. Partial checkpoints: a processor that answers READY but persists only up to an *earlier* entry.
//    Example: entries A, B and C carry 700, 50 and 400 asset ids; at C the processor looks up the first
//    1000 ids in one remote call, persists the results — which cover A and B — and keeps C's remainder
//    buffered for the next batch. For that, READY must carry a checkpoint token that was handed out with
//    the entry (sequence number + run identity: feed pointers are not mutually ordered, and a token from a
//    previous run must be rejected fail-fast), and the consumer commits that token's pointer instead of its
//    own pendingPointer — the commit point is already an internal parameter, see the commit path in
//    FeedConsumerImpl. Note: the counter default "processed = offered entries" only holds when committing
//    at the last offered entry.
//
// 3. An implementer can write a FeedProcessor that keeps state across commits, because one instance lives
//    for the whole run. Example: a cache of the asset ids it already looked up and persisted this run, so a
//    later batch skips the remote lookup for ids it has already seen. No framework work is needed for this.
//    A cache that should survive *runs* is different: that would live in the (singleton) handler bean.

/**
 * <b>Framework-internal; not a developer SPI.</b> The processing engine behind the public handler tiers: one
 * fresh instance per run, fed the decoded entries one by one ({@link #processEntry}, outside any transaction) and
 * offered a checkpoint opportunity on every page boundary, when the safety net fires, at the end of the feed,
 * on a clean interruption and when reading the next entry fails ({@link #onCheckpointOpportunity}, with the
 * {@link CheckpointReason reason}).
 *
 * <p>The processor answers with its overall {@link State}; the consumer translates that into what happens to
 * the transaction and the feed pointer. Answering {@link State#BUFFERING} to an opportunity is a legitimate
 * refusal — the framework never forces a wrap-up; refusing at {@link CheckpointReason#END_OF_FEED} /
 * {@link CheckpointReason#INTERRUPTED} / {@link CheckpointReason#READ_FAILURE} means discard-and-redo (the
 * state is lost and the next run re-reads from the pinned pointer).
 *
 * <p>All callbacks run on the feed thread (single-threaded, no synchronization needed). The processor applies
 * its handler's {@code accepts} filter itself and keeps the {@link #accepted()} / {@link #processed()}
 * counters; the consumer counts what was read.
 *
 * @param <C> the domain type of the entry content
 */
interface FeedProcessor<C> {

    /** The overall state of the processor after a callback — and with it what the consumer has to do. */
    enum State {

        /** No uncommitted state. The consumer may checkpoint the feed pointer freely (on a boundary). */
        IDLE,

        /** Uncommitted state, but do not persist now. The pointer stays pinned, so a crash re-offers the work. */
        BUFFERING,

        /**
         * Ready to persist: the consumer opens the transaction and calls {@link #persist()} in it, together
         * with the feed pointer write — batch effect and pointer atomically.
         */
        READY
    }

    /** Why the consumer offers a checkpoint opportunity. */
    enum CheckpointReason {

        /** All entries of a page have been offered. */
        PAGE_BOUNDARY,

        /**
         * The safety net: the uncommitted re-read window has grown past the feed's {@code maxUncommittedPages}.
         * Replaces {@link #PAGE_BOUNDARY} on every boundary as long as the window is exceeded.
         */
        WINDOW_EXHAUSTED,

        /** The head of the feed has been reached (no younger events at this moment; also on a 304 run). */
        END_OF_FEED,

        /** The run is being cleanly interrupted (deactivation/shutdown). */
        INTERRUPTED,

        /**
         * Reading the next entry failed (page fetch or content decode) and the run is about to fail. One
         * last opportunity to wrap up what is already buffered — that work is in hand and processable, so
         * committing it now beats re-reading it on the next run.
         */
        READ_FAILURE
    }

    /** The next entry is offered, outside any transaction. Slow work (phase 1) happens in here. */
    State processEntry(ProcessingEntry<C> entry);

    /** A checkpoint opportunity, outside any transaction. Wrapping up a partial batch (phase 1) happens in here. */
    State onCheckpointOpportunity(CheckpointReason reason);

    /**
     * Persist the prepared work (phase 2). Called <em>only</em> by the consumer after a {@link State#READY}
     * answer, inside the transaction that also advances the feed pointer. If this throws, that transaction
     * rolls back and the run fails (the pointer does not advance → the work is offered again next run).
     */
    void persist();

    /** Cumulative count of the entries this run that passed the handler's {@code accepts} filter. */
    int accepted();

    /**
     * Cumulative processed counter of this run: the realised, <em>committed</em> work. A free measure — the
     * processor chooses its meaning (default: entries; see {@link ProcessResult}).
     */
    int processed();
}
