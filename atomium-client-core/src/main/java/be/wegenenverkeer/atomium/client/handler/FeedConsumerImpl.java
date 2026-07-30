package be.wegenenverkeer.atomium.client.handler;

import be.wegenenverkeer.atomium.client.fetch.AtomiumClient;
import be.wegenenverkeer.atomium.client.fetch.FeedPointer;
import be.wegenenverkeer.atomium.client.fetch.FetchEntry;
import be.wegenenverkeer.atomium.client.fetch.FetchResult;
import be.wegenenverkeer.atomium.client.protocol.AtomiumEntry;
import be.wegenenverkeer.atomium.client.handler.FeedHandlerController.Code;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Default {@link FeedConsumer}: consumes one feed for one {@link FeedHandler}. Pure reader: scheduling and
 * start/stop lifecycle deliberately live in {@link FeedRunner}. The only lifecycle coupling is the
 * {@code isInterrupted} of {@link #run}.
 *
 * <p>The consumer does not talk to the handler directly, but to a {@link FeedHandlerController}: it owns the
 * buffer and reports a {@link Code buffer state} per callback; the consumer translates that code into what
 * happens with the transaction and the feedPointer (see {@link ReadRun#apply}).
 */
class FeedConsumerImpl<T> implements FeedConsumer, EntryPusher {

    private final String feedId;
    private final FeedHandler<T> handler;                            // only still needed for pushEntry
    private final Supplier<FeedHandlerController<T>> controllers;    // a fresh controller per run
    private final AtomiumClient atomiumClient;
    private final FeedContentDecoder<T> feedContentDecoder;
    private final FeedPointerRepository feedPointerRepository;
    private final FeedTransactions transactions;
    // The start position for a brand-new feed (configuration-driven); only consulted as long as the repo has
    // no pointer yet. The decision about the strategy lives with whoever assembles the Feed, not here.
    private final Supplier<FeedPointer> initialFeedPointer;
    private final FeedEventListener listeners;

    public FeedConsumerImpl(String feedId, FeedHandler<T> handler, Supplier<FeedHandlerController<T>> controllers,
                            AtomiumClient atomiumClient, FeedContentDecoder<T> feedContentDecoder,
                            FeedPointerRepository feedPointerRepository, FeedTransactions transactions,
                            Supplier<FeedPointer> initialFeedPointer, FeedEventListener listeners) {
        this.feedId = feedId;
        this.handler = handler;
        this.controllers = controllers;
        this.atomiumClient = atomiumClient;
        this.feedContentDecoder = feedContentDecoder;
        this.feedPointerRepository = feedPointerRepository;
        this.transactions = transactions;
        this.initialFeedPointer = initialFeedPointer;
        this.listeners = listeners;
    }

    /**
     * Process a raw content item as if it had been on the feed: decode it and offer it (inside one transaction,
     * just like the normal processing) to {@link FeedHandler#pushEntry}. Does <em>not</em> advance the feed pointer
     * (the item was not really on the feed) and deliberately <em>bypasses the controller</em>: a push is not a
     * feed entry and therefore does not belong in a batch.
     */
    @Override
    public void pushEntry(String rawContent) {
        transactions.inTransactionWithoutResult(() -> {
            T content = feedContentDecoder.readFeedContent(rawContent);
            handler.pushEntry(content);
        });
    }

    private FeedPointer readFeedPointer() {
        return feedPointerRepository.find(feedId).orElseGet(initialFeedPointer);
    }

    /**
     * Read the feed from the (persisted) feedPointer up to the head. Per page the entries are offered to the
     * {@link FeedHandlerController} in read order, followed by {@code onEndOfPage}; at the end of the feed
     * {@code onEndOfFeed} follows.
     *
     * <p>The effect of the handler is committed inside one transaction together with the advanced feedPointer, so
     * that on a crash no processed event is lost and no event that was already committed is offered again.
     * <em>When</em> that transaction opens is determined by the buffer state the controller reports;
     * during an HTTP fetch no transaction is ever open.
     *
     * <p>After every commit point {@code isInterrupted} is consulted; if it is {@code true}, the run stops cleanly.
     * The next run resumes from the persisted pointer.
     *
     * <p><b>All</b> {@link FeedEventListener} events (except {@code runFailed}, which comes from the {@link FeedRunner})
     * are emitted here, and always after the commit point — never from the {@link FeedHandlerController}, because it
     * runs inside the flush transaction and therefore does not know whether the commit succeeds. The controller
     * <em>reports</em> (what was processed, and the counters); the consumer emits. On a failure the consumer rethrows —
     * decode/handler errors wrapped with entry context — and the runner emits the {@code runFailed} event plus its
     * ERROR log.
     */
    @Override
    public void run(BooleanSupplier isInterrupted) {
        ReadRun readRun = new ReadRun(isInterrupted);
        listeners.runStarted(feedId, readRun.startPosition());
        // runCompleted/runInterrupted is emitted by the read loop itself, at the right end point: a normal end
        // (304 or head reached) → runCompleted, an interruption → runInterrupted. Never both.
        readRun.read();
    }

    /**
     * One run: the read loop plus its state. A fresh instance per run, so that the controller's buffer and the
     * pointer bookkeeping never linger between runs.
     *
     * <p>The pointer bookkeeping is two-part, and that is the core of the batch model:
     * <ul>
     *   <li>{@code pendingPointer} — where we <em>would</em> be: the pointer after the last entry offered to the
     *       controller (or the page pointer on a boundary). Always moves along.</li>
     *   <li>{@code persistedPointer} — where we <em>really</em> are: what is in the DB. Only moves along on a
     *       commit. As long as the buffer contains uncommitted work, it stays pinned, so that a crash simply
     *       offers those entries again.</li>
     * </ul>
     */
    private final class ReadRun {

        private final BooleanSupplier isInterrupted;
        private final FeedHandlerController<T> controller = controllers.get();
        private final FeedPointer startPosition;
        private FeedPointer pendingPointer;
        private FeedPointer persistedPointer;
        // the reference point for the per-commit deltas in feedPointerAdvanced
        private FeedRunResult previousCommit = new FeedRunResult(0, 0, 0);

        ReadRun(BooleanSupplier isInterrupted) {
            this.isInterrupted = isInterrupted;
            this.startPosition = readFeedPointer();
            this.pendingPointer = startPosition;
            this.persistedPointer = startPosition;
        }

        FeedPointer startPosition() {
            return startPosition;
        }

        /** The actual read loop; returns the counters of this run (which the controller keeps). */
        FeedRunResult read() {
            FeedPointer feedPointer = persistedPointer;
            while (true) {
                FetchResult page = atomiumClient.fetch(feedPointer).orElse(null);
                if (page == null) {   // 304 Not Modified: nothing new since the previous poll
                    apply(controller.onEndOfFeed(), true);
                    listeners.feedNotModified(feedId);
                    FeedRunResult result = controller.result();
                    listeners.runCompleted(feedId, result);
                    return result;
                }
                listeners.pageFetched(feedId, page.feedPageMetadata(), page.fetchEntries().size());
                for (FetchEntry fetchEntry : page.fetchEntries()) {
                    processEntry(page, fetchEntry);
                    if (isInterrupted.getAsBoolean()) {
                        return endWithInterruption();
                    }
                }
                processEndOfPage(page);
                // Advance the page-level pointer to the next page (also for a completely empty page),
                // otherwise we keep refetching the same page.
                feedPointer = page.nextFeedPointer();
                listeners.pageProcessed(feedId, page.feedPageMetadata());
                if (!page.feedHasMorePages()) {
                    return endWithEndOfFeed();
                }
                if (isInterrupted.getAsBoolean()) {
                    return endWithInterruption();
                }
            }
        }

        private void processEntry(FetchResult page, FetchEntry fetchEntry) {
            T content = decode(fetchEntry.entry());
            Code code = controller.onEntry(new BatchEntry<>(page.feedPageMetadata(), fetchEntry.entry(), content));
            pendingPointer = fetchEntry.nextFeedPointer();
            apply(code, false);
        }

        private void processEndOfPage(FetchResult page) {
            Code code = controller.onEndOfPage(page.feedPageMetadata());
            pendingPointer = page.nextFeedPointer();
            apply(code, true);
        }

        private FeedRunResult endWithEndOfFeed() {
            apply(controller.onEndOfFeed(), true);
            listeners.endOfFeedReached(feedId);
            FeedRunResult result = controller.result();
            listeners.runCompleted(feedId, result);
            return result;
        }

        private FeedRunResult endWithInterruption() {
            apply(controller.onInterrupted(), true);
            FeedRunResult result = controller.result();
            listeners.runInterrupted(feedId, result);
            return result;
        }

        /**
         * Translate the buffer state into what happens with the transaction, and emit the corresponding events —
         * always <em>after</em> the commit, never inside it. {@code onBoundary} = we are on a page/feed boundary or
         * an interruption; only there may an empty buffer checkpoint its pointer.
         *
         * <p>An exception from {@code flush()} (so from the handler) makes the {@link FeedTransactions} roll back and
         * rethrows: the run failed, the pointer does not advance, nothing is emitted, and the {@link FeedRunner}
         * engages its backoff.
         */
        private void apply(Code code, boolean onBoundary) {
            switch (code) {
                // buffer not ready yet → persist nothing: the pointer stays pinned, so that the buffered
                // entries are delivered again on the next run
                case BUFFERING -> { }

                // batch effect and pointer atomically in one transaction; the events only after the commit
                case BUFFER_COMPLETE -> {
                    List<BatchEntry<T>> processed = flushAndPersist();
                    listeners.entriesProcessed(feedId, processed);
                    listeners.feedPointerAdvanced(feedId, persistedPointer, deltaSincePreviousCommit());
                }

                // Nothing to process. On a boundary we checkpoint the pointer, so that an empty (or entirely
                // filtered-out) tail is not fetched again on every poll. In the middle of a page's entries we
                // deliberately postpone that. If the pointer is already where it should be (e.g. a 304), we do
                // not write needlessly.
                case BUFFER_EMPTY -> {
                    if (onBoundary && !pendingPointer.equals(persistedPointer)) {
                        transactions.inTransactionWithoutResult(() -> writeFeedPointer(pendingPointer));
                        persistedPointer = pendingPointer;
                        listeners.feedPointerAdvanced(feedId, persistedPointer, deltaSincePreviousCommit());
                    }
                }
            }
        }

        /** The counters of what was added since the previous commit; the committed state becomes the new reference point. */
        private FeedRunResult deltaSincePreviousCommit() {
            FeedRunResult cumulative = controller.result();
            FeedRunResult delta = new FeedRunResult(
                    cumulative.read() - previousCommit.read(),
                    cumulative.accepted() - previousCommit.accepted(),
                    cumulative.processed() - previousCommit.processed());
            previousCommit = cumulative;
            return delta;
        }

        /** The batch and the pointer in one transaction; returns what was actually processed (post-accepts, post-dedup). */
        private List<BatchEntry<T>> flushAndPersist() {
            List<BatchEntry<T>> processed = transactions.inTransaction(() -> {
                List<BatchEntry<T>> flushed = controller.flush();
                writeFeedPointer(pendingPointer);
                return flushed;
            });
            persistedPointer = pendingPointer;
            return processed;
        }

        private void writeFeedPointer(FeedPointer feedPointer) {
            feedPointerRepository.save(feedId, feedPointer);
        }

        private T decode(AtomiumEntry entry) {
            try {
                return feedContentDecoder.readFeedContent(entry.content().value());
            } catch (RuntimeException e) {
                throw new FeedEntryProcessingException(feedId, entry.id(), FeedEntryPhase.DECODE, e);
            }
        }
    }
}
