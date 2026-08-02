package be.wegenenverkeer.atomium.client.handler;

import be.wegenenverkeer.atomium.client.fetch.AtomiumClient;
import be.wegenenverkeer.atomium.client.fetch.FeedPointer;
import be.wegenenverkeer.atomium.client.fetch.FetchEntry;
import be.wegenenverkeer.atomium.client.fetch.FetchResult;
import be.wegenenverkeer.atomium.client.handler.FeedProcessor.CheckpointReason;
import be.wegenenverkeer.atomium.client.handler.FeedProcessor.State;
import be.wegenenverkeer.atomium.client.protocol.AtomiumEntry;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Default {@link FeedConsumer}: consumes one feed for one {@link FeedHandler}. Pure reader: scheduling and
 * start/stop lifecycle deliberately live in {@link FeedRunner}. The only lifecycle coupling is the
 * {@code isInterrupted} of {@link #run}.
 *
 * <p>The consumer does not talk to the handler directly, but to a {@link FeedProcessor}: it does the
 * processing and answers with its {@link State overall state}; the consumer translates that state into what
 * happens with the transaction and the feedPointer (see {@link ReadRun}).
 */
class FeedConsumerImpl<T> implements FeedConsumer, EntryPusher {

    private static final Logger LOG = LoggerFactory.getLogger(FeedConsumerImpl.class);

    private final String feedId;
    private final FeedHandler<T> handler;                     // only still needed for pushEntry
    private final Supplier<FeedProcessor<T>> processors;      // a fresh processor per run
    private final int maxUncommittedPages;
    private final AtomiumClient atomiumClient;
    private final FeedContentDecoder<T> feedContentDecoder;
    private final FeedPointerRepository feedPointerRepository;
    private final FeedTransactions transactions;
    // The start position for a brand-new feed (configuration-driven); only consulted as long as the repo has
    // no pointer yet. The decision about the strategy lives with whoever assembles the Feed, not here.
    private final Supplier<FeedPointer> initialFeedPointer;
    private final FeedEventListener listeners;

    public FeedConsumerImpl(String feedId, FeedHandler<T> handler, Supplier<FeedProcessor<T>> processors,
                            int maxUncommittedPages, AtomiumClient atomiumClient,
                            FeedContentDecoder<T> feedContentDecoder,
                            FeedPointerRepository feedPointerRepository, FeedTransactions transactions,
                            Supplier<FeedPointer> initialFeedPointer, FeedEventListener listeners) {
        this.feedId = feedId;
        this.handler = handler;
        this.processors = processors;
        this.maxUncommittedPages = maxUncommittedPages;
        this.atomiumClient = atomiumClient;
        this.feedContentDecoder = feedContentDecoder;
        this.feedPointerRepository = feedPointerRepository;
        this.transactions = transactions;
        this.initialFeedPointer = initialFeedPointer;
        this.listeners = listeners;
    }

    /**
     * Process a raw content item as if it had been on the feed: decode it and offer it (inside one transaction,
     * just like the normal processing) to the handler's {@link FeedPusher#pushEntry}. Does <em>not</em> advance
     * the feed pointer (the item was not really on the feed) and deliberately <em>bypasses the processor</em>:
     * a push is not a feed entry and therefore does not belong in a batch.
     */
    @Override
    public void pushEntry(String rawContent) {
        if (!(handler instanceof FeedPusher)) {
            throw new UnsupportedOperationException(
                    "this handler does not support pushing entries; implement FeedPusher to support it");
        }
        @SuppressWarnings("unchecked")   // FeedPusher's contract: C is the handler's own content type
        FeedPusher<T> pusher = (FeedPusher<T>) handler;
        transactions.inTransactionWithoutResult(() -> {
            T content = feedContentDecoder.readFeedContent(rawContent);
            pusher.pushEntry(content);
        });
    }

    private FeedPointer readFeedPointer() {
        return feedPointerRepository.find(feedId).orElseGet(initialFeedPointer);
    }

    /**
     * Read the feed from the (persisted) feedPointer up to the head. Per page the entries are offered to the
     * {@link FeedProcessor} in read order; every boundary (page boundary, safety net, end of feed — also on a
     * 304 — and interruption) offers the processor one checkpoint opportunity, with the strongest applicable
     * {@link CheckpointReason reason}. A failing read (page fetch or decode) offers one last opportunity
     * before the run fails, so buffered work is committed instead of re-read (see {@code wrapUpBeforeFailing}).
     *
     * <p>The effect of the handler is committed inside one transaction together with the advanced feedPointer, so
     * that on a crash no processed event is lost and no event that was already committed is offered again.
     * <em>When</em> that transaction opens is determined by the state the processor answers; during an HTTP
     * fetch no transaction is ever open.
     *
     * <p>After every commit point {@code isInterrupted} is consulted; if it is {@code true}, the run stops cleanly.
     * The next run resumes from the persisted pointer.
     *
     * <p><b>All</b> {@link FeedEventListener} events (except {@code runFailed}, which comes from the {@link FeedRunner})
     * are emitted here, and always after the commit point — never from the {@link FeedProcessor}, because it
     * cannot know whether the commit succeeds. The processor <em>reports</em> (its state and the counters); the
     * consumer emits. On a failure the consumer rethrows — decode and handler errors wrapped with entry context
     * (the decode wrap happens here, the handler wrap in the processors) — and the
     * runner emits the {@code runFailed} event plus its ERROR log.
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
     * One run: the read loop plus its state. A fresh instance per run, so that the processor's state and the
     * pointer bookkeeping never linger between runs.
     *
     * <p>The pointer bookkeeping is two-part, and that is the core of the processing model:
     * <ul>
     *   <li>{@code pendingPointer} — where we <em>would</em> be: the pointer after the last entry offered to the
     *       processor (or the page pointer on a boundary). Always moves along.</li>
     *   <li>{@code persistedPointer} — where we <em>really</em> are: what is in the DB. Only moves along on a
     *       commit. As long as the processor holds uncommitted work, it stays pinned, so that a crash simply
     *       offers those entries again.</li>
     * </ul>
     *
     * <p>On a page or feed boundary {@code pendingPointer} is the <b>page pointer</b>
     * ({@code page.nextFeedPointer()}), never the entry-level pointer of the last entry: the entry pointer
     * re-fetches the same page with a filter, the page pointer jumps to the next page with the etag — commit
     * the entry pointer on a boundary and every poll of a quiet feed re-fetches its head instead of getting a
     * 304.
     */
    // Maintainer note — the commit path below always persists at the pointer the consumer is holding at the
    // READY answer (the last offered entry, promoted to the page pointer on a boundary). That is a property of
    // the current processors, not of the seam: the commit point stays an internal parameter (pendingPointer),
    // so a future processor that persists up to an earlier entry only needs to hand the consumer a different
    // pointer here — see the partial-checkpoints note on FeedProcessor.
    private final class ReadRun {

        private final BooleanSupplier isInterrupted;
        private final FeedProcessor<T> processor = processors.get();
        private final FeedPointer startPosition;
        private FeedPointer pendingPointer;
        private FeedPointer persistedPointer;
        private int read;
        // the reference point for the per-commit deltas in feedPointerAdvanced
        private FeedRunResult previousCommit = new FeedRunResult(0, 0, 0);
        // the updated of the youngest entry offered since the previous commit (what the next commit covers)
        private @Nullable OffsetDateTime latestOfferedUpdated;
        // the safety net: pages fully offered since the last commit; once at maxUncommittedPages, every
        // boundary offers WINDOW_EXHAUSTED instead of PAGE_BOUNDARY until a commit resets the window
        private int pagesSinceCommit;
        // a declined WINDOW_EXHAUSTED opportunity is logged once per episode, not once per page
        private boolean windowExhaustedDeclineLogged;

        ReadRun(BooleanSupplier isInterrupted) {
            this.isInterrupted = isInterrupted;
            this.startPosition = readFeedPointer();
            this.pendingPointer = startPosition;
            this.persistedPointer = startPosition;
        }

        FeedPointer startPosition() {
            return startPosition;
        }

        /** The actual read loop; returns the counters of this run. */
        FeedRunResult read() {
            FeedPointer feedPointer = persistedPointer;
            while (true) {
                FetchResult page;
                try {
                    page = atomiumClient.fetch(feedPointer).orElse(null);
                } catch (RuntimeException readFailure) {
                    throw wrapUpBeforeFailing(readFailure);
                }
                if (page == null) {   // 304 Not Modified: nothing new since the previous poll
                    offerCheckpoint(CheckpointReason.END_OF_FEED);
                    listeners.feedNotModified(feedId);
                    FeedRunResult result = result();
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
                boolean endOfFeed = !page.feedHasMorePages();
                processEndOfPage(page, endOfFeed);
                // Advance the page-level pointer to the next page (also for a completely empty page),
                // otherwise we keep refetching the same page.
                feedPointer = page.nextFeedPointer();
                listeners.pageProcessed(feedId, page.feedPageMetadata());
                if (endOfFeed) {
                    listeners.endOfFeedReached(feedId);
                    FeedRunResult result = result();
                    listeners.runCompleted(feedId, result);
                    return result;
                }
                if (isInterrupted.getAsBoolean()) {
                    return endWithInterruption();
                }
            }
        }

        private void processEntry(FetchResult page, FetchEntry fetchEntry) {
            AtomiumEntry entry = fetchEntry.entry();
            T content;
            try {
                content = decode(entry);
            } catch (RuntimeException readFailure) {
                throw wrapUpBeforeFailing(readFailure);
            }
            read++;
            latestOfferedUpdated = latest(latestOfferedUpdated, entry.updated());
            State state = processor.processEntry(new ProcessingEntry<>(page.feedPageMetadata(), entry, content));
            pendingPointer = fetchEntry.nextFeedPointer();
            // mid-page only a READY answer does anything: an idle processor is deliberately not checkpointed
            // here (no needless pointer writes in the middle of a page), a buffering one pins the pointer
            if (state == State.READY) {
                commit();
            }
        }

        /**
         * One checkpoint opportunity per boundary, with the strongest reason: {@code END_OF_FEED} on the last
         * page, otherwise {@code WINDOW_EXHAUSTED} while the safety-net window is exceeded, otherwise
         * {@code PAGE_BOUNDARY}. The pending pointer is the page pointer here (see the class javadoc).
         */
        private void processEndOfPage(FetchResult page, boolean endOfFeed) {
            pendingPointer = page.nextFeedPointer();
            pagesSinceCommit++;
            CheckpointReason reason = endOfFeed ? CheckpointReason.END_OF_FEED
                    : pagesSinceCommit >= maxUncommittedPages ? CheckpointReason.WINDOW_EXHAUSTED
                    : CheckpointReason.PAGE_BOUNDARY;
            offerCheckpoint(reason);
        }

        private FeedRunResult endWithInterruption() {
            offerCheckpoint(CheckpointReason.INTERRUPTED);
            FeedRunResult result = result();
            listeners.runInterrupted(feedId, result);
            return result;
        }

        /**
         * Offer the processor a checkpoint opportunity and translate its answer:
         * <ul>
         *   <li>{@code READY} — commit: effect and pointer atomically in one transaction;</li>
         *   <li>{@code IDLE} — nothing to persist: checkpoint the pointer (a plain pointer write, so that an
         *       empty or entirely filtered-out stretch is not fetched again on every poll) — unless it is
         *       already where it should be (e.g. a 304): no needless writes;</li>
         *   <li>{@code BUFFERING} — a legitimate refusal: the pointer stays pinned. Declining the safety net
         *       is logged once per exceedance episode; declining at {@code END_OF_FEED}/{@code INTERRUPTED}
         *       means the buffered work is discarded and re-read next run.</li>
         * </ul>
         */
        private void offerCheckpoint(CheckpointReason reason) {
            State state = processor.onCheckpointOpportunity(reason);
            switch (state) {
                case READY -> commit();
                case IDLE -> {
                    if (!pendingPointer.equals(persistedPointer)) {
                        transactions.inTransactionWithoutResult(() -> writeFeedPointer(pendingPointer));
                        afterCommit();
                    }
                }
                case BUFFERING -> {
                    if (reason == CheckpointReason.WINDOW_EXHAUSTED && !windowExhaustedDeclineLogged) {
                        LOG.warn("feed '{}': {} pages read without a commit and the processor declines to wrap "
                                        + "up; the feed pointer stays pinned and the re-read window keeps growing",
                                feedId, pagesSinceCommit);
                        windowExhaustedDeclineLogged = true;
                    }
                }
            }
        }

        /**
         * Reading the next entry failed (page fetch or content decode): offer the processor one last
         * checkpoint opportunity, so that work it already buffered is committed instead of re-read on the
         * next run — the safety net exists for crashes, not for a failing source. The run still fails
         * afterwards, with the read failure. If the wrap-up itself fails too, that processing failure is
         * primary — it concerns the oldest events, the ones a retry hits first — and the read failure
         * travels along as a suppressed exception.
         */
        private RuntimeException wrapUpBeforeFailing(RuntimeException readFailure) {
            try {
                offerCheckpoint(CheckpointReason.READ_FAILURE);
            } catch (RuntimeException processingFailure) {
                processingFailure.addSuppressed(readFailure);
                return processingFailure;
            }
            return readFailure;
        }

        /** The prepared work and the pointer in one transaction; an exception rolls back and fails the run. */
        private void commit() {
            transactions.inTransactionWithoutResult(() -> {
                processor.persist();
                writeFeedPointer(pendingPointer);
            });
            afterCommit();
        }

        /** Shared post-commit bookkeeping: the pointer advanced, the safety-net window and delta reset. */
        private void afterCommit() {
            persistedPointer = pendingPointer;
            pagesSinceCommit = 0;
            windowExhaustedDeclineLogged = false;
            OffsetDateTime covered = latestOfferedUpdated;
            latestOfferedUpdated = null;
            listeners.feedPointerAdvanced(feedId, persistedPointer, deltaSincePreviousCommit(), covered);
        }

        /** The counters of what was added since the previous commit; the committed state becomes the new reference point. */
        private FeedRunResult deltaSincePreviousCommit() {
            FeedRunResult cumulative = result();
            FeedRunResult delta = new FeedRunResult(
                    cumulative.read() - previousCommit.read(),
                    cumulative.accepted() - previousCommit.accepted(),
                    cumulative.processed() - previousCommit.processed());
            previousCommit = cumulative;
            return delta;
        }

        /** The run counters: read is counted here, accepted/processed by the processor (it applies {@code accepts}). */
        private FeedRunResult result() {
            return new FeedRunResult(read, processor.accepted(), processor.processed());
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

        private static @Nullable OffsetDateTime latest(@Nullable OffsetDateTime a, OffsetDateTime b) {
            return a == null || b.isAfter(a) ? b : a;
        }
    }
}
