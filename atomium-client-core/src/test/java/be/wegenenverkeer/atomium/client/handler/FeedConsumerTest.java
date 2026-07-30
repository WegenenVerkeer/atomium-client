package be.wegenenverkeer.atomium.client.handler;

import be.wegenenverkeer.atomium.client.fetch.AtomiumClient;
import be.wegenenverkeer.atomium.client.fetch.FakeFeedHttpClient;
import be.wegenenverkeer.atomium.client.fetch.FeedPointer;
import be.wegenenverkeer.atomium.client.fetch.JacksonFeedPageDecoder;
import be.wegenenverkeer.atomium.client.protocol.AtomiumEntry;
import be.wegenenverkeer.atomium.client.protocol.FeedPageMetadata;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Functional test of the handler API: verifies that a {@link FeedHandler} receives the correct callbacks when
 * the feed consumer walks the (in-memory) feed, that the {@link FeedEventListener}s see the right event timeline,
 * and that the feedPointer sits at the right position at every commit point so that a second run resumes where the
 * first one stopped. Runs on the real fetch API on top of a {@link FakeFeedHttpClient} (no HTTP, no threads: an
 * inline executor lets {@code tryToStart()} run the run synchronously).
 *
 * <p>The pointer asserts go through {@code eventListener.pointerCommits()}: {@code feedPointerAdvanced} fires on
 * every commit and guaranteed only after the commit, so that list is exactly what a crash at any moment would leave
 * behind. That a pointer did <em>not</em> advance shows in the absence of a commit.
 *
 * <p>The Spring Boot module contains an end-to-end counterpart ({@code FeedConsumerWireMockTest}) that proves the
 * same scenarios through the full stack (autoconfig → HTTP → JDBC → transactions).
 */
class FeedConsumerTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final FeedContentDecoder<TestFeedEntry> CONTENT_DECODER =
            value -> MAPPER.readValue(value, TestFeedEntry.class);

    private final RecordingEntryFeedHandler handler = new RecordingEntryFeedHandler();
    private final RecordingBatchedFeedHandler batchHandler = new RecordingBatchedFeedHandler();
    private final RecordingFeedEventListener eventListener = new RecordingFeedEventListener();
    private final InterruptingFeedEventListener interruptingListener = new InterruptingFeedEventListener();
    private final RecordingFeedTransactions transactions = new RecordingFeedTransactions();

    /**
     * The handler SPI: a {@link be.wegenenverkeer.atomium.client.handler.EntryFeedHandler} receives the entries
     * one by one, in read order. The lifecycle (page boundary, end of feed) is <em>not</em> a handler callback —
     * you observe that through the {@link FeedEventListener} (see {@link Events}).
     */
    @Nested
    class EntryFeedHandler {

        @Test
        void receivesTheEntriesOneByOneInReadOrder() {
            FeedRuntime runtime = runtime(handler, completeFeed());

            consume(runtime);

            assertThat(handler.invocations()).containsExactly(
                    "onEntry(/0, id-001, fieldValue-1)",
                    "onEntry(/0, id-002, fieldValue-2)",
                    "onEntry(/0, id-003, fieldValue-3)",
                    "onEntry(/1, id-004, fieldValue-4)",
                    "onEntry(/1, id-005, fieldValue-5)",
                    "onEntry(/1, id-006, fieldValue-6)",
                    "onEntry(/2, id-007, fieldValue-7)",
                    "onEntry(/2, id-008, fieldValue-8)");
        }

        /** One commit per entry: the effect and the new position sit together in one transaction. */
        @Test
        void commitsPerEntry() {
            FeedRuntime runtime = runtime(handler, completeFeed());

            consume(runtime);

            assertThat(eventListener.pointerCommits()).containsExactly(
                    "lastEvent=/0#id-001 nextFetch=/0?after=id-001",
                    "lastEvent=/0#id-002 nextFetch=/0?after=id-002",
                    // the last entry of a page immediately gets the pointer to the next page
                    "lastEvent=/0#id-003 nextFetch=/1",
                    "lastEvent=/1#id-004 nextFetch=/1?after=id-004",
                    "lastEvent=/1#id-005 nextFetch=/1?after=id-005",
                    "lastEvent=/1#id-006 nextFetch=/2",
                    "lastEvent=/2#id-007 nextFetch=/2?after=id-007",
                    "lastEvent=/2#id-008 nextFetch=/2?after=id-008");
            // and every commit went through the transaction layer
            assertThat(transactions.commits()).isEqualTo(8);
        }

        /**
         * A complete, <em>empty</em> middle page (e.g. with a "filtered" feed): the consumer must navigate
         * through it instead of refetching it forever — for the handler it simply does not exist.
         */
        @Test
        void skipsACompleteEmptyMiddlePage() {
            FeedRuntime runtime = runtime(handler, new FakeFeedHttpClient().head("/2")
                    .page("/0", resource("empty-page/0.json"))
                    .page("/1", resource("empty-page/1.json"))   // empty
                    .page("/2", resource("empty-page/2.json")));

            consume(runtime);

            assertThat(handler.invocations()).containsExactly(
                    "onEntry(/0, id-001, fieldValue-1)",
                    "onEntry(/0, id-002, fieldValue-2)",
                    "onEntry(/0, id-003, fieldValue-3)",
                    "onEntry(/2, id-004, fieldValue-4)",
                    "onEntry(/2, id-005, fieldValue-5)");

            // the empty page delivers no entry, but does yield a checkpoint (otherwise every poll would refetch it)
            assertThat(eventListener.events()).containsSequence(
                    "pageFetched(/1, 0)",
                    "feedPointerAdvanced(lastEvent=/0#id-003 nextFetch=/2)",
                    "pageProcessed(/1)");
        }
    }

    /** The {@link FeedEventListener} SPI: the full timeline of a run. */
    @Nested
    class Events {

        @Test
        void emitsTheFullEventSequence() {
            FeedRuntime runtime = runtime(handler, completeFeed());

            consume(runtime);

            assertThat(eventListener.events()).containsExactly(
                    "runStarted",
                    "pageFetched(/0, 3)",
                    "entriesProcessed(id-001)", "feedPointerAdvanced(lastEvent=/0#id-001 nextFetch=/0?after=id-001)",
                    "entriesProcessed(id-002)", "feedPointerAdvanced(lastEvent=/0#id-002 nextFetch=/0?after=id-002)",
                    "entriesProcessed(id-003)", "feedPointerAdvanced(lastEvent=/0#id-003 nextFetch=/1)",
                    "pageProcessed(/0)",
                    "pageFetched(/1, 3)",
                    "entriesProcessed(id-004)", "feedPointerAdvanced(lastEvent=/1#id-004 nextFetch=/1?after=id-004)",
                    "entriesProcessed(id-005)", "feedPointerAdvanced(lastEvent=/1#id-005 nextFetch=/1?after=id-005)",
                    "entriesProcessed(id-006)", "feedPointerAdvanced(lastEvent=/1#id-006 nextFetch=/2)",
                    "pageProcessed(/1)",
                    "pageFetched(/2, 2)",
                    "entriesProcessed(id-007)", "feedPointerAdvanced(lastEvent=/2#id-007 nextFetch=/2?after=id-007)",
                    "entriesProcessed(id-008)", "feedPointerAdvanced(lastEvent=/2#id-008 nextFetch=/2?after=id-008)",
                    "pageProcessed(/2)",
                    "endOfFeedReached",
                    "runCompleted(read=8, accepted=8, processed=8)");
        }

        /** A failed run (here an unreadable page) emits {@code runFailed} with the failures counter. */
        @Test
        void aFailedRunEmitsRunFailedWithCounter() {
            FakeFeedHttpClient source = completeFeed();
            FeedRuntime runtime = runtime(handler, source);
            consume(runtime);   // run 1 succeeds → the pointer sits in the repository
            eventListener.reset();

            // the source now delivers nonsense
            source.page("/2", "this is not a valid feed page");

            consume(runtime);

            // the failure occurs while fetching/decoding the page (no entry context) → runStarted + runFailed(1)
            assertThat(eventListener.events()).containsExactly("runStarted", "runFailed(1)");
        }

        /**
         * Edge case: the start position of a <em>brand-new</em> feed is determined lazily (a call to the source,
         * see {@code initialFeedPointer}). If that one fails, the run never really began: there is no start position
         * to fill {@code runStarted} with, so only {@code runFailed} follows. Once a pointer has been persisted,
         * this can no longer happen.
         */
        @Test
        void whenEvenTheStartPositionCannotBeDeterminedOnlyRunFailedFollows() {
            FeedRuntime runtime = runtime(handler, new FakeFeedHttpClient().head("/0")
                    .page("/0", "this is not a valid feed page"));

            consume(runtime);

            assertThat(eventListener.events()).containsExactly("runFailed(1)");
        }
    }

    /**
     * The {@link BatchedFeedHandler} variant: entries are buffered in a {@link FeedHandlerBatch}, deduplicated,
     * and only processed in one go at the threshold (or at the end of the feed) — together with the feed pointer,
     * in one transaction.
     *
     * <p>The batch feed deliberately carries duplicates. Page {@code /0}, in read order: id-001=alfa, id-002=beta,
     * id-003=alfa, id-004=gamma, id-005=beta, id-006=alfa; the head {@code /1}: id-007=delta. Deduplication is on
     * {@code aField}, with threshold 3 ({@code preferredBatchSize}).
     */
    @Nested
    class BatchedFeedHandler {

        /**
         * With threshold 3 (three <em>distinct</em> values) the first batch flushes at id-004: alfa has been seen
         * three times by then but counts once, and of alfa the <em>last</em> entry remains (id-003, not id-001). The
         * order is that of first appearance (alfa, beta, gamma).
         */
        @Test
        void flushesAtTheThresholdAndDeduplicatesLastWins() {
            FeedRuntime runtime = batchRuntime();

            consume(runtime);

            assertThat(batchHandler.invocations()).containsExactly(
                    "onBatch(id-003=alfa, id-002=beta, id-004=gamma)",
                    "onBatch(id-005=beta, id-006=alfa, id-007=delta)");
        }

        /**
         * The core of the model: as long as the batch has not been flushed, there is <em>no</em> commit — not even on
         * a page boundary. So there are exactly two commits, each up to and including the entry that triggered the
         * flush. A crash then repeats the whole uncommitted batch, which is the intent.
         */
        @Test
        void pinsThePointerAsLongAsTheBatchIsNotFlushed() {
            FeedRuntime runtime = batchRuntime();

            consume(runtime);

            assertThat(eventListener.pointerCommits()).containsExactly(
                    "lastEvent=/0#id-004 nextFetch=/0?after=id-004",
                    "lastEvent=/1#id-007 nextFetch=/1?after=id-007");

            // and the batch really spans the page boundary: no commit between pageProcessed(/0) and the second flush
            assertThat(eventListener.events()).containsSequence(
                    "pageProcessed(/0)",
                    "pageFetched(/1, 1)",
                    "entriesProcessed(id-005, id-006, id-007)");
        }

        /** The three counters diverge as soon as deduplication kicks in: 7 read, 7 accepted, 6 processed. */
        @Test
        void reportsReadAcceptedAndProcessedSeparately() {
            FeedRuntime runtime = batchRuntime();

            consume(runtime);

            assertThat(eventListener.events()).endsWith("runCompleted(read=7, accepted=7, processed=6)");
        }

        /**
         * Every commit carries the delta since the previous commit ({@code feedPointerAdvanced}), so metrics
         * already show progress during a long run. The deltas together = the run result.
         */
        @Test
        void everyCommitReportsTheDeltaSinceThePrevious() {
            FeedRuntime runtime = batchRuntime();

            consume(runtime);

            // batch threshold 3: flush after the third distinct key (4 read due to a dedup), then the rest
            assertThat(eventListener.commitDeltas()).containsExactly(
                    "read=4, accepted=4, processed=3",
                    "read=3, accepted=3, processed=3");
        }

        /** The runtime tracks the progress: when the last commit happened and how fresh the last event is. */
        @Test
        void theRuntimeTracksLastCommitAndLastEvent() {
            FeedRuntime runtime = batchRuntime();
            assertThat(runtime.lastCommit()).isNull();
            assertThat(runtime.lastEvent()).isNull();

            consume(runtime);

            assertThat(runtime.lastCommit()).isNotNull();
            assertThat(runtime.lastEvent()).isNotNull();
        }

        /**
         * A feed all of whose events are irrelevant ({@code accepts} → {@code false}): the handler is never
         * invoked, but the pointer <em>does</em> advance — on the page boundaries. Without that checkpoint every
         * poll would refetch the whole irrelevant tail; that is the reason it exists.
         */
        @Test
        void filteredOutEntriesDoNotReachTheHandlerButAdvanceThePointer() {
            FeedRuntime runtime = batchRuntime();
            batchHandler.acceptOnly(content -> false);

            consume(runtime);

            assertThat(batchHandler.invocations()).isEmpty();
            assertThat(eventListener.events()).doesNotContain("entriesProcessed()");
            // only checkpoints on the page boundaries — deliberately not mid-page
            assertThat(eventListener.pointerCommits()).containsExactly(
                    "lastEvent=/0#id-006 nextFetch=/1",
                    "lastEvent=/1#id-007 nextFetch=/1?after=id-007");
            assertThat(eventListener.events()).endsWith("runCompleted(read=7, accepted=0, processed=0)");
        }

        /**
         * Filtering and deduplication combined: only the alfa events count (3 of the 7), and of those one remains
         * (id-006). The threshold of 3 is thus never reached — but at the end of the feed the incomplete batch is
         * still flushed. A batch does not survive polls.
         */
        @Test
        void anIncompleteBatchIsStillFlushedAtTheEndOfTheFeed() {
            FeedRuntime runtime = batchRuntime();
            batchHandler.acceptOnly(content -> "alfa".equals(content.aField()));

            consume(runtime);

            assertThat(batchHandler.invocations()).containsExactly("onBatch(id-006=alfa)");
            // one single commit, all the way at the end: until then the pointer stayed at the start position
            assertThat(eventListener.pointerCommits()).containsExactly(
                    "lastEvent=/1#id-007 nextFetch=/1?after=id-007");
            assertThat(eventListener.events()).endsWith(
                    "entriesProcessed(id-006)",
                    "feedPointerAdvanced(lastEvent=/1#id-007 nextFetch=/1?after=id-007)",
                    "endOfFeedReached",
                    "runCompleted(read=7, accepted=3, processed=1)");
        }

        private FeedRuntime batchRuntime() {
            return runtime(batchHandler, batchFeed(), feed -> feed.preferredBatchSize(3));
        }
    }

    /**
     * The <b>page safety net</b> ({@code maxUnflushedPages}). A strongly filtering or deduplicating feed rarely
     * reaches its threshold — and as long as nothing is flushed, the feed pointer does not advance. Without the
     * safety net the window a crash has to re-read thus grows unbounded.
     *
     * <p>The safety-net feed isolates exactly that: threshold 100 (never reached) and {@code maxUnflushedPages}
     * 2, across three pages (id-001..id-005, all distinct).
     */
    @Nested
    class SafetyNet {

        @Test
        void forcesAFlushAfterMaxUnflushedPages() {
            FeedRuntime runtime = safetyNetRuntime();

            consume(runtime);

            // the first batch spans two pages and is flushed by the safety net (not by the threshold);
            // the rest follows at the end of the feed
            assertThat(batchHandler.invocations()).containsExactly(
                    "onBatch(id-001=a, id-002=b, id-003=c, id-004=d)",
                    "onBatch(id-005=e)");
        }

        /** And so: the uncommitted window stays bounded to two pages. */
        @Test
        void boundsTheUncommittedWindow() {
            FeedRuntime runtime = safetyNetRuntime();

            consume(runtime);

            assertThat(eventListener.pointerCommits()).containsExactly(
                    "lastEvent=/1#id-004 nextFetch=/2",     // ← the safety net, after two pages
                    "lastEvent=/2#id-005 nextFetch=/2?after=id-005");

            // page /0 by itself yields no commit: the page counter only reaches the threshold at the second page boundary
            assertThat(eventListener.events()).containsSequence(
                    "pageFetched(/0, 2)",
                    "pageProcessed(/0)",
                    "pageFetched(/1, 2)",
                    "entriesProcessed(id-001, id-002, id-003, id-004)");
        }

        private FeedRuntime safetyNetRuntime() {
            return runtime(batchHandler, batchCapFeed(),
                    feed -> feed.preferredBatchSize(100).maxUnflushedPages(2));
        }
    }

    /**
     * A graceful interruption (deactivation/shutdown) in the middle of a run: the consumer stops after the next
     * commit point. The contract is that nothing is lost and nothing is duplicated — the next run resumes exactly
     * where this one stopped. The {@link InterruptingFeedEventListener} forces the interruption point.
     */
    @Nested
    class Interruption {

        /** Interrupted mid-page: the pointer sits at the last committed entry, no further. */
        @Test
        void midPageStopsAfterTheLastCommittedEntry() {
            FeedRuntime runtime = runtime(handler, completeFeed());
            interruptingListener.interruptAfter("entriesProcessed(id-002)", () -> runtime.runner().deactivate());

            consume(runtime);

            assertThat(handler.invocations()).containsExactly(
                    "onEntry(/0, id-001, fieldValue-1)",
                    "onEntry(/0, id-002, fieldValue-2)");
            // an interrupted run ends with runInterrupted (no runCompleted after it — that is only the normal end)
            assertThat(eventListener.events()).endsWith(
                    "runInterrupted(read=2, accepted=2, processed=2)");
            assertThat(eventListener.pointerCommits()).last()
                    .isEqualTo("lastEvent=/0#id-002 nextFetch=/0?after=id-002");

            // and the next run resumes seamlessly: id-003 (nothing lost, nothing duplicated)
            handler.reset();
            eventListener.reset();

            consume(runtime);

            assertThat(handler.invocations()).startsWith("onEntry(/0, id-003, fieldValue-3)");
        }

        /** Interrupted on a page boundary: the pointer is checkpointed on the next page. */
        @Test
        void onAPageBoundaryStopsAfterTheCheckpoint() {
            FeedRuntime runtime = runtime(handler, completeFeed());
            interruptingListener.interruptAfter("pageProcessed(/0)", () -> runtime.runner().deactivate());

            consume(runtime);

            assertThat(eventListener.events()).endsWith(
                    "pageProcessed(/0)",
                    "runInterrupted(read=3, accepted=3, processed=3)");
            assertThat(eventListener.pointerCommits()).last().isEqualTo("lastEvent=/0#id-003 nextFetch=/1");

            // the next run starts right on page /1
            handler.reset();

            consume(runtime);

            assertThat(handler.invocations()).startsWith("onEntry(/1, id-004, fieldValue-4)");
        }
    }

    /**
     * The failure paths. The stakes are the same every time and are the core of the transaction model: if an entry
     * does not get processed, the feed pointer must <em>not</em> advance past that entry — otherwise it would be
     * silently skipped.
     */
    @Nested
    class FailurePaths {

        /** An entry that cannot be decoded (phase DECODE): the run failed, the pointer stays at the previous entry. */
        @Test
        void anUnreadableEntryLeavesThePointerInPlace() {
            FeedRuntime runtime = runtime(handler, new FakeFeedHttpClient().head("/2")
                    .page("/0", resource("0-broken-entry.json"))
                    .page("/2", resource("2-v1.json")));

            consume(runtime);

            // id-001 is processed and committed; id-002 fails while decoding → the run stops there
            assertThat(handler.invocations()).containsExactly("onEntry(/0, id-001, fieldValue-1)");
            assertThat(eventListener.pointerCommits())
                    .containsExactly("lastEvent=/0#id-001 nextFetch=/0?after=id-001");
            assertThat(eventListener.events()).endsWith("runFailed(1)");
        }

        /**
         * A handler that throws (phase HANDLER): the transaction around {@code flush()} + the pointer write rolls
         * back, so the pointer stays at the previous entry and id-002 is delivered again on the next run. Also, no
         * {@code entriesProcessed} is emitted — the events follow the commit, not the attempt.
         */
        @Test
        void aFailingHandlerRollsThePointerBack() {
            FeedRuntime runtime = runtime(handler, completeFeed());
            handler.failAt("id-002");

            consume(runtime);

            assertThat(handler.invocations()).containsExactly("onEntry(/0, id-001, fieldValue-1)");
            assertThat(eventListener.pointerCommits())
                    .containsExactly("lastEvent=/0#id-001 nextFetch=/0?after=id-001");
            assertThat(eventListener.events())
                    .containsExactly(
                            "runStarted",
                            "pageFetched(/0, 3)",
                            "entriesProcessed(id-001)",
                            "feedPointerAdvanced(lastEvent=/0#id-001 nextFetch=/0?after=id-001)",
                            "runFailed(1)");
            // and the failed flush went through the transaction layer as a rollback
            assertThat(transactions.rollbacks()).isEqualTo(1);
        }
    }

    /**
     * A poll on an unchanged head: the source answers {@code 304 Not Modified} (based on the etag the consumer
     * carries in its pointer). The run delivers nothing and leaves the pointer alone — and reports that as
     * {@code feedNotModified}, not as {@code endOfFeedReached} (there we did fetch the head).
     */
    @Test
    void aPollOnAnUnchangedFeedDeliversNothing() {
        // the head carries an etag, so the consumer keeps it in its pointer and the refetch yields a 304
        FeedRuntime runtime = runtime(handler, new FakeFeedHttpClient().head("/2")
                .page("/0", resource("0.json"))
                .page("/1", resource("1.json"))
                .page("/2", resource("2-v1.json"), "\"v1\""));

        consume(runtime);   // run 1: the whole feed

        handler.reset();
        eventListener.reset();

        consume(runtime);   // run 2: the head is unchanged → 304

        assertThat(handler.invocations()).isEmpty();
        assertThat(eventListener.pointerCommits()).isEmpty();   // no needless write
        assertThat(eventListener.events()).containsExactly(
                "runStarted", "feedNotModified", "runCompleted(read=0, accepted=0, processed=0)");
    }

    /**
     * The atomicity core of the transaction model, proven explicitly: the handler effect and the pointer write
     * happen <em>within the same transaction scope</em> (not merely "in the right order").
     */
    @Nested
    class Transactions {

        @Test
        void handlerEffectAndPointerWriteHappenWithinTheSameTransaction() {
            List<String> scopes = new ArrayList<>();
            InMemoryFeedPointerRepository realRepo = new InMemoryFeedPointerRepository();
            FeedPointerRepository guardedRepo = new FeedPointerRepository() {
                @Override
                public Optional<FeedPointer> find(String feedId) {
                    return realRepo.find(feedId);
                }

                @Override
                public void save(String feedId, FeedPointer feedPointer) {
                    scopes.add("save(inTransaction=%s)".formatted(transactions.isInTransaction()));
                    realRepo.save(feedId, feedPointer);
                }
            };
            RecordingEntryFeedHandler guardedHandler = new RecordingEntryFeedHandler() {
                @Override
                public void onEntry(FeedPageMetadata pageMetadata, AtomiumEntry entry, TestFeedEntry content) {
                    scopes.add("onEntry(%s, inTransaction=%s)".formatted(entry.id(), transactions.isInTransaction()));
                }
            };
            FeedRuntime runtime = runtime(guardedHandler, completeFeed(),
                    feed -> feed.pointerRepository(guardedRepo));

            consume(runtime);

            // per entry: first the handler effect, then the pointer write — both within the transaction
            assertThat(scopes).startsWith(
                    "onEntry(id-001, inTransaction=true)", "save(inTransaction=true)",
                    "onEntry(id-002, inTransaction=true)", "save(inTransaction=true)");
            assertThat(scopes).hasSize(16)   // 8 entries × (handler + pointer write)
                    .allSatisfy(scope -> assertThat(scope).contains("inTransaction=true"));
        }
    }

    /**
     * The {@code EntryPusher}: process a raw content item as if it were on the feed — decode + invoke the handler,
     * within one transaction, but deliberately <em>without</em> advancing the feed pointer and without events
     * (the item was not really on the feed).
     */
    @Nested
    class Push {

        @Test
        void decodesAndInvokesTheHandlerWithinATransaction() {
            List<String> pushes = new ArrayList<>();
            RecordingEntryFeedHandler pushable = new RecordingEntryFeedHandler() {
                @Override
                public void pushEntry(TestFeedEntry content) {
                    pushes.add("%s (inTransaction=%s)".formatted(content.aField(), transactions.isInTransaction()));
                }
            };
            FeedRuntime runtime = runtime(pushable, completeFeed());

            runtime.pusher().pushEntry("{\"aField\": \"recovered-042\"}");

            assertThat(pushes).containsExactly("recovered-042 (inTransaction=true)");
            assertThat(transactions.commits()).isEqualTo(1);
            // a push is not a feed entry: no events and no pointer commit
            assertThat(eventListener.events()).isEmpty();
            assertThat(eventListener.pointerCommits()).isEmpty();
        }

        @Test
        void unreadableContentThrowsAndRollsBackWithoutTouchingTheHandler() {
            List<String> pushes = new ArrayList<>();
            RecordingEntryFeedHandler pushable = new RecordingEntryFeedHandler() {
                @Override
                public void pushEntry(TestFeedEntry content) {
                    pushes.add(content.aField());
                }
            };
            FeedRuntime runtime = runtime(pushable, completeFeed());

            assertThatThrownBy(() -> runtime.pusher().pushEntry("this is not json"))
                    .isInstanceOf(RuntimeException.class);

            assertThat(pushes).isEmpty();
            assertThat(transactions.rollbacks()).isEqualTo(1);
        }

        /** Without a {@code pushEntry} override a handler does not support push (the default throws). */
        @Test
        void withoutOverrideTheHandlerDoesNotSupportPush() {
            FeedRuntime runtime = runtime(handler, completeFeed());

            assertThatThrownBy(() -> runtime.pusher().pushEntry("{\"aField\": \"x\"}"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    /** The complete feed (pages /0, /1 and the head /2) with 8 entries. */
    private static FakeFeedHttpClient completeFeed() {
        return new FakeFeedHttpClient().head("/2")
                .page("/0", resource("0.json"))
                .page("/1", resource("1.json"))
                .page("/2", resource("2-v1.json"));
    }

    /** The batch feed: page /0 with duplicates (alfa/beta/gamma) and the head /1 (delta). */
    private static FakeFeedHttpClient batchFeed() {
        return new FakeFeedHttpClient().head("/1")
                .page("/0", resource("batch/0.json"))
                .page("/1", resource("batch/1.json"));
    }

    /** The safety-net feed: three pages (/0, /1 and the head /2), five entries, all distinct. */
    private static FakeFeedHttpClient batchCapFeed() {
        return new FakeFeedHttpClient().head("/2")
                .page("/0", resource("batchcap/0.json"))
                .page("/1", resource("batchcap/1.json"))
                .page("/2", resource("batchcap/2.json"));
    }

    private FeedRuntime runtime(FeedHandler<TestFeedEntry> feedHandler, FakeFeedHttpClient source) {
        return runtime(feedHandler, source, feed -> { });
    }

    /**
     * Builds the {@link Feed} and assembles the runtime: the real fetch API on top of the {@link FakeFeedHttpClient},
     * an inline executor (synchronous runs), and the recording/interrupting listeners. The pointer repository is the
     * (non-persistent) builder default — fresh per test, which does let runs within a single test resume.
     */
    private FeedRuntime runtime(FeedHandler<TestFeedEntry> feedHandler, FakeFeedHttpClient source,
                                Consumer<Feed.Builder<TestFeedEntry>> tune) {
        AtomiumClient atomiumClient = new AtomiumClient(source, new JacksonFeedPageDecoder());
        Feed.Builder<TestFeedEntry> builder = Feed
                .builder(feedHandler.getFeedId(), feedHandler, atomiumClient, CONTENT_DECODER)
                .transactions(transactions)
                .initialFeedPointer(atomiumClient::pointerToOldest)
                .executor(Runnable::run)
                .addListener(eventListener)
                .addListener(interruptingListener);
        tune.accept(builder);
        return FeedRuntime.of(builder.build());
    }

    /**
     * Activate the feed and trigger one run. Thanks to the direct (inline) executor the run executes synchronously
     * on this thread, so we can assert right after it.
     */
    private static void consume(FeedRuntime runtime) {
        runtime.runner().activate();
        assertThat(runtime.runner().tryToStart()).isTrue();
    }

    /** Loads a feed page JSON from {@code src/test/resources/feedpages/}. */
    private static String resource(String file) {
        String path = "/feedpages/" + file;
        try (InputStream in = FeedConsumerTest.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("resource not found: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
