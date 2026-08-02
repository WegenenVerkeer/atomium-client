package be.wegenenverkeer.atomium.client.handler;

import be.wegenenverkeer.atomium.client.exception.AtomiumHttpException;
import be.wegenenverkeer.atomium.client.fetch.AtomiumClient;
import be.wegenenverkeer.atomium.client.fetch.FakeFeedHttpClient;
import be.wegenenverkeer.atomium.client.fetch.FeedPointer;
import be.wegenenverkeer.atomium.client.fetch.JacksonFeedPageDecoder;
import be.wegenenverkeer.atomium.client.handler.FeedProcessor.CheckpointReason;
import be.wegenenverkeer.atomium.client.handler.FeedProcessor.State;
import be.wegenenverkeer.atomium.client.protocol.AtomiumEntry;
import be.wegenenverkeer.atomium.client.protocol.FeedPageMetadata;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static ch.qos.logback.classic.Level.WARN;
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
 * <p>Where the seam needs behavior the two real processors never show (a processor that <em>declines</em>
 * checkpoint opportunities), the run loop is driven with a {@link ScriptedFeedProcessor} on a directly
 * constructed consumer — still the real run loop, just a scripted processor behind the seam.
 *
 * <p>The Spring Boot module contains an end-to-end counterpart ({@code FeedConsumerWireMockTest}) that proves the
 * same scenarios through the full stack (autoconfig → HTTP → JDBC → transactions).
 */
class FeedConsumerTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final FeedContentDecoder<TestFeedEntry> CONTENT_DECODER =
            value -> MAPPER.readValue(value, TestFeedEntry.class);

    private final RecordingEntryFeedHandler handler = new RecordingEntryFeedHandler();
    private final RecordingSimpleBatchedFeedHandler batchHandler = new RecordingSimpleBatchedFeedHandler();
    private final RecordingFeedEventListener eventListener = new RecordingFeedEventListener();
    private final InterruptingFeedEventListener interruptingListener = new InterruptingFeedEventListener();
    private final RecordingFeedTransactions transactions = new RecordingFeedTransactions();

    /**
     * The handler SPI: an {@link be.wegenenverkeer.atomium.client.handler.EntryFeedHandler} receives the entries
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
         * A tail of entries {@code accepts} rejects: the handler is never invoked for them, but the pointer is
         * checkpointed past them on the boundaries — deliberately not mid-page (no needless pointer writes), and
         * without a checkpoint every poll would refetch the whole irrelevant tail.
         */
        @Test
        void anAcceptsFilteredTailIsCheckpointedOnTheBoundaries() {
            FeedRuntime runtime = runtime(handler, completeFeed());
            handler.acceptOnly(content -> Set.of("fieldValue-1", "fieldValue-2", "fieldValue-3")
                    .contains(content.aField()));

            consume(runtime);

            assertThat(handler.invocations()).containsExactly(
                    "onEntry(/0, id-001, fieldValue-1)",
                    "onEntry(/0, id-002, fieldValue-2)",
                    "onEntry(/0, id-003, fieldValue-3)");
            // three per-entry commits, then only the boundary checkpoints of the filtered pages
            assertThat(eventListener.pointerCommits()).containsExactly(
                    "lastEvent=/0#id-001 nextFetch=/0?after=id-001",
                    "lastEvent=/0#id-002 nextFetch=/0?after=id-002",
                    "lastEvent=/0#id-003 nextFetch=/1",
                    "lastEvent=/1#id-006 nextFetch=/2",
                    "lastEvent=/2#id-008 nextFetch=/2?after=id-008");
            assertThat(eventListener.events()).endsWith("runCompleted(read=8, accepted=3, processed=3)");
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
                    "feedPointerAdvanced(lastEvent=/0#id-001 nextFetch=/0?after=id-001)",
                    "feedPointerAdvanced(lastEvent=/0#id-002 nextFetch=/0?after=id-002)",
                    "feedPointerAdvanced(lastEvent=/0#id-003 nextFetch=/1)",
                    "pageProcessed(/0)",
                    "pageFetched(/1, 3)",
                    "feedPointerAdvanced(lastEvent=/1#id-004 nextFetch=/1?after=id-004)",
                    "feedPointerAdvanced(lastEvent=/1#id-005 nextFetch=/1?after=id-005)",
                    "feedPointerAdvanced(lastEvent=/1#id-006 nextFetch=/2)",
                    "pageProcessed(/1)",
                    "pageFetched(/2, 2)",
                    "feedPointerAdvanced(lastEvent=/2#id-007 nextFetch=/2?after=id-007)",
                    "feedPointerAdvanced(lastEvent=/2#id-008 nextFetch=/2?after=id-008)",
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

        /** The runtime tracks the progress: when the last commit happened and how fresh the last covered event is. */
        @Test
        void theRuntimeTracksLastCommitAndLastEvent() {
            FeedRuntime runtime = runtime(handler, completeFeed());
            assertThat(runtime.lastCommit()).isNull();
            assertThat(runtime.lastEvent()).isNull();

            consume(runtime);

            assertThat(runtime.lastCommit()).isNotNull();
            assertThat(runtime.lastEvent()).isNotNull();
        }
    }

    /**
     * The {@link SimpleBatchedProcessingFeedHandler} tier: accepted entries are buffered up to the processing
     * threshold ({@code preferredProcessingSize}) and then processed in two phases — {@code process} outside any
     * transaction, {@code persist} inside the transaction that also advances the feed pointer.
     *
     * <p>The batch feed: page {@code /0} carries id-001=alfa, id-002=beta, id-003=alfa, id-004=gamma,
     * id-005=beta, id-006=alfa; the head {@code /1} carries id-007=delta.
     */
    @Nested
    class BatchTier {

        /**
         * Threshold 3: the first batch wraps up mid-page (at id-003, entry-level pointer), the second at the
         * last entry of the page, and the leftover partial batch at the end of the feed. Each {@code persist}
         * line echoes the prepared state its {@code process} returned — {@code P} travels intact between the
         * two phases.
         */
        @Test
        void processesAtTheThresholdAndWrapsUpThePartialBatchAtTheEndOfTheFeed() {
            FeedRuntime runtime = batchRuntime(3);

            consume(runtime);

            assertThat(batchHandler.invocations()).containsExactly(
                    "process(id-001=alfa, id-002=beta, id-003=alfa)",
                    "persist(id-001=alfa, id-002=beta, id-003=alfa)",
                    "process(id-004=gamma, id-005=beta, id-006=alfa)",
                    "persist(id-004=gamma, id-005=beta, id-006=alfa)",
                    "process(id-007=delta)",
                    "persist(id-007=delta)");
        }

        /**
         * The core of the model: as long as the batch has not been wrapped up, there is <em>no</em> commit — not
         * even on a page boundary. A crash then repeats the whole uncommitted batch, which is the intent.
         */
        @Test
        void pinsThePointerWhileTheBatchSpansAPageBoundary() {
            FeedRuntime runtime = batchRuntime(100);   // threshold never reached → one batch, wrapped up at the end
            consume(runtime);

            assertThat(batchHandler.invocations()).containsExactly(
                    "process(id-001=alfa, id-002=beta, id-003=alfa, id-004=gamma, id-005=beta, id-006=alfa, "
                            + "id-007=delta)",
                    "persist(id-001=alfa, id-002=beta, id-003=alfa, id-004=gamma, id-005=beta, id-006=alfa, "
                            + "id-007=delta)");
            // one single commit, all the way at the end: until then the pointer stayed at the start position
            assertThat(eventListener.pointerCommits()).containsExactly(
                    "lastEvent=/1#id-007 nextFetch=/1?after=id-007");
        }

        /**
         * {@code accepts}-filtering: irrelevant entries do not enter the batch and do not count toward the
         * threshold. Only the alfa entries count (id-001, id-003, id-006), so threshold 2 is reached at id-003 —
         * not at id-002.
         */
        @Test
        void filteredOutEntriesDoNotCountTowardTheThreshold() {
            FeedRuntime runtime = batchRuntime(2);
            batchHandler.acceptOnly(content -> "alfa".equals(content.aField()));

            consume(runtime);

            assertThat(batchHandler.invocations()).containsExactly(
                    "process(id-001=alfa, id-003=alfa)",
                    "persist(id-001=alfa, id-003=alfa)",
                    "process(id-006=alfa)",
                    "persist(id-006=alfa)");
        }

        /**
         * A feed all of whose events are irrelevant: the handler is never invoked, but the pointer <em>does</em>
         * advance on the boundaries — reporting zeros (see {@link Counters}). Without that checkpoint every poll
         * would refetch the whole irrelevant tail.
         */
        @Test
        void anAllFilteredTailStillCheckpoints() {
            FeedRuntime runtime = batchRuntime(3);
            batchHandler.acceptOnly(content -> false);

            consume(runtime);

            assertThat(batchHandler.invocations()).isEmpty();
            assertThat(eventListener.pointerCommits()).containsExactly(
                    "lastEvent=/0#id-006 nextFetch=/1",
                    "lastEvent=/1#id-007 nextFetch=/1?after=id-007");
            assertThat(eventListener.events()).endsWith("runCompleted(read=7, accepted=0, processed=0)");
        }

        /** A clean interruption wraps up the partial batch, and the next run resumes seamlessly after it. */
        @Test
        void anInterruptionWrapsUpThePartialBatch() {
            FeedRuntime runtime = batchRuntime(100);
            batchHandler.whenOffered("id-004", () -> runtime.runner().deactivate());

            consume(runtime);

            assertThat(batchHandler.invocations()).containsExactly(
                    "process(id-001=alfa, id-002=beta, id-003=alfa, id-004=gamma)",
                    "persist(id-001=alfa, id-002=beta, id-003=alfa, id-004=gamma)");
            assertThat(eventListener.events()).endsWith("runInterrupted(read=4, accepted=4, processed=4)");
            assertThat(eventListener.pointerCommits()).last()
                    .isEqualTo("lastEvent=/0#id-004 nextFetch=/0?after=id-004");

            // the next run resumes seamlessly: id-005 (nothing lost, nothing duplicated)
            consume(runtime);

            assertThat(batchHandler.invocations()).endsWith(
                    "process(id-005=beta, id-006=alfa, id-007=delta)",
                    "persist(id-005=beta, id-006=alfa, id-007=delta)");
        }

        /** The two phases against the transaction scope: {@code process} outside, {@code persist} inside. */
        @Test
        void processRunsOutsideAndPersistInsideTheTransaction() {
            FeedRuntime runtime = batchRuntime(3);
            batchHandler.recordTransactionScope(transactions);

            consume(runtime);

            assertThat(batchHandler.invocations()).startsWith(
                    "process(id-001=alfa, id-002=beta, id-003=alfa) [inTransaction=false]",
                    "persist(id-001=alfa, id-002=beta, id-003=alfa) [inTransaction=true]");
        }

        /** A failure in {@code process} (outside the transaction): the run fails, the pointer never advanced. */
        @Test
        void aFailureInProcessFailsTheRunWithThePointerUntouched() {
            FeedRuntime runtime = batchRuntime(3);
            batchHandler.failInProcess();

            consume(runtime);

            assertThat(eventListener.pointerCommits()).isEmpty();
            assertThat(eventListener.events()).endsWith("runFailed(1)");
            // process runs outside any transaction: nothing to roll back
            assertThat(transactions.rollbacks()).isZero();
            assertThat(transactions.commits()).isZero();
        }

        /** A failure in {@code persist}: the transaction rolls back, the pointer stays, the run fails. */
        @Test
        void aFailureInPersistRollsBackAndFailsTheRun() {
            FeedRuntime runtime = batchRuntime(3);
            batchHandler.failInPersist();

            consume(runtime);

            assertThat(batchHandler.invocations()).containsExactly(
                    "process(id-001=alfa, id-002=beta, id-003=alfa)");
            assertThat(eventListener.pointerCommits()).isEmpty();
            assertThat(eventListener.events()).endsWith("runFailed(1)");
            assertThat(transactions.rollbacks()).isEqualTo(1);
        }
    }

    /**
     * The pointer positions per commit — the promotion rule in particular: a commit on a page/feed boundary
     * writes the <b>page pointer</b> (jump to the next page, with the etag), never the entry-level pointer
     * (re-fetch of the same page with a filter). Committing the entry pointer on a boundary would make every
     * poll of a quiet feed re-fetch its head instead of getting a 304.
     */
    @Nested
    class PointersAndBoundaries {

        /** A mid-page commit writes the entry pointer; a commit at the last entry of a page the page pointer. */
        @Test
        void aMidPageCommitWritesTheEntryPointerABoundaryCommitThePagePointer() {
            FeedRuntime runtime = batchRuntime(3);

            consume(runtime);

            assertThat(eventListener.pointerCommits()).containsExactly(
                    "lastEvent=/0#id-003 nextFetch=/0?after=id-003",   // mid-page: same page, filtered
                    "lastEvent=/0#id-006 nextFetch=/1",                 // page boundary: jump to the next page
                    "lastEvent=/1#id-007 nextFetch=/1?after=id-007");   // head: poll position (with etag if any)
        }

        /**
         * The etag optimisation stays intact through a boundary commit: the head is committed with its etag, so
         * the next poll gets a 304 and the processed page is never re-fetched.
         */
        @Test
        void aBoundaryCommitKeepsTheEtagSoTheNextPollGetsA304() {
            FeedRuntime runtime = runtime(batchHandler, new FakeFeedHttpClient().head("/1")
                            .page("/0", resource("batch/0.json"))
                            .page("/1", resource("batch/1.json"), "\"v1\""),
                    feed -> feed.preferredProcessingSize(100));

            consume(runtime);   // run 1: one batch, wrapped up and committed on the feed boundary
            eventListener.reset();

            consume(runtime);   // run 2: the head is unchanged → 304

            assertThat(eventListener.events()).containsExactly(
                    "runStarted", "feedNotModified", "runCompleted(read=0, accepted=0, processed=0)");
            assertThat(eventListener.pointerCommits()).isEmpty();   // and no needless pointer write
        }

        /**
         * A poll on an unchanged head leaves the pointer alone — and reports that as {@code feedNotModified},
         * not as {@code endOfFeedReached} (there we did fetch the head).
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
    }

    /**
     * The <b>safety net</b> ({@code maxUncommittedPages}). A strongly filtering feed rarely reaches its
     * threshold — and as long as nothing is committed, the feed pointer does not advance, so the window a crash
     * has to re-read grows unbounded. Once the window is exceeded, every boundary offers the processor a
     * {@code WINDOW_EXHAUSTED} opportunity (instead of {@code PAGE_BOUNDARY}) until a commit resets it.
     *
     * <p>The safety-net feed: three pages ({@code /0} id-001=a, id-002=b; {@code /1} id-003=c, id-004=d; the
     * head {@code /2} id-005=e).
     */
    @Nested
    class SafetyNet {

        /** The batch tier wraps up on {@code WINDOW_EXHAUSTED}: better half a batch than an unbounded window. */
        @Test
        void theBatchTierWrapsUpWhenTheWindowIsExhausted() {
            FeedRuntime runtime = runtime(batchHandler, safetyNetFeed(),
                    feed -> feed.preferredProcessingSize(100).maxUncommittedPages(2));

            consume(runtime);

            // the first batch spans two pages and is wrapped up by the safety net (not by the threshold);
            // the rest follows at the end of the feed
            assertThat(batchHandler.invocations()).containsExactly(
                    "process(id-001=a, id-002=b, id-003=c, id-004=d)",
                    "persist(id-001=a, id-002=b, id-003=c, id-004=d)",
                    "process(id-005=e)",
                    "persist(id-005=e)");
            // and so the uncommitted window stays bounded: the safety-net commit sits on the page pointer
            assertThat(eventListener.pointerCommits()).containsExactly(
                    "lastEvent=/1#id-004 nextFetch=/2",
                    "lastEvent=/2#id-005 nextFetch=/2?after=id-005");
        }

        /**
         * The reasons at the seam: one opportunity per boundary, {@code WINDOW_EXHAUSTED} replacing
         * {@code PAGE_BOUNDARY} as soon as the window is exceeded, and the end of the feed always
         * {@code END_OF_FEED}.
         */
        @Test
        void windowExhaustedReplacesPageBoundaryOnEveryBoundaryWhileExceeded() {
            ScriptedFeedProcessor processor = new ScriptedFeedProcessor();
            processor.answerOpportunities(reason -> State.BUFFERING);

            scriptedRun(() -> processor, safetyNetFeed(), 2);

            assertThat(processor.callbacks()).containsExactly(
                    "onEntry(id-001)", "onEntry(id-002)",
                    "opportunity(PAGE_BOUNDARY)",
                    "onEntry(id-003)", "onEntry(id-004)",
                    "opportunity(WINDOW_EXHAUSTED)",
                    "onEntry(id-005)",
                    "opportunity(END_OF_FEED)");
        }

        /**
         * Declining the safety net is legitimate: the pointer stays pinned — and the framework logs the decline
         * once per exceedance episode, not once per page.
         */
        @Test
        void aDecliningProcessorPinsThePointerAndTheDeclineIsLoggedOncePerEpisode() {
            ScriptedFeedProcessor processor = new ScriptedFeedProcessor();
            processor.answerOpportunities(reason -> State.BUFFERING);
            ListAppender<ILoggingEvent> log = captureConsumerLog();

            scriptedRun(() -> processor, safetyNetFeed(), 1);   // every boundary exceeds the window

            assertThat(processor.callbacks()).contains(
                    "opportunity(WINDOW_EXHAUSTED)", "opportunity(WINDOW_EXHAUSTED)");
            assertThat(eventListener.pointerCommits()).isEmpty();   // pinned, exactly as the processor asked
            assertThat(log.list).filteredOn(event -> event.getLevel() == WARN).hasSize(1);
        }

        /**
         * Declining at the end of the run means discard-and-redo: the state is lost and the next run re-reads
         * everything from the pinned pointer.
         */
        @Test
        void decliningAtTheEndOfTheRunMeansDiscardAndRedo() {
            List<ScriptedFeedProcessor> processors = new ArrayList<>();
            Supplier<FeedProcessor<TestFeedEntry>> fresh = () -> {
                ScriptedFeedProcessor processor = new ScriptedFeedProcessor();
                processor.answerOpportunities(reason -> State.BUFFERING);
                processors.add(processor);
                return processor;
            };
            FeedConsumerImpl<TestFeedEntry> consumer = scriptedConsumer(fresh, safetyNetFeed(), 10);

            consumer.run(() -> false);   // run 1: everything read, nothing committed
            consumer.run(() -> false);   // run 2: re-reads from the pinned pointer

            assertThat(eventListener.pointerCommits()).isEmpty();
            assertThat(processors).hasSize(2);
            assertThat(processors.get(1).callbacks()).startsWith("onEntry(id-001)");
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
            interruptingListener.interruptAfter("committed(id-002)", () -> runtime.runner().deactivate());

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
    /**
     * A failing read (page fetch or decode) is not a crash: the events read so far are in hand and
     * processable. The consumer therefore offers one last checkpoint opportunity ({@code READ_FAILURE})
     * before the run fails, so buffered work is committed instead of re-read on the next run — the safety
     * net exists for crashes, not for a failing source.
     */
    @Nested
    class ReadFailure {

        /** The fetch of the next page fails: the buffered batch is still wrapped up, then the run fails. */
        @Test
        void aFailingPageFetchStillWrapsUpTheBufferedBatch() {
            // the middle page /1 is not registered → fetching it fails after page /0 is fully buffered
            FeedRuntime runtime = runtime(batchHandler, new FakeFeedHttpClient().head("/2")
                    .page("/0", resource("batchcap/0.json"))
                    .page("/2", resource("batchcap/2.json")),
                    feed -> feed.preferredProcessingSize(100));

            consume(runtime);

            assertThat(batchHandler.invocations()).containsExactly(
                    "process(id-001=a, id-002=b)",
                    "persist(id-001=a, id-002=b)");
            assertThat(eventListener.pointerCommits()).containsExactly("lastEvent=/0#id-002 nextFetch=/1");
            assertThat(eventListener.events()).endsWith("runFailed(1)");
        }

        /** A decode failure mid-page: the entries buffered before the poison entry are still committed. */
        @Test
        void aDecodeFailureStillWrapsUpTheEntriesBufferedBeforeIt() {
            FeedRuntime runtime = runtime(batchHandler, new FakeFeedHttpClient().head("/2")
                    .page("/0", resource("0-broken-entry.json"))
                    .page("/2", resource("2-v1.json")),
                    feed -> feed.preferredProcessingSize(100));

            consume(runtime);

            // id-001 is committed; the run fails on id-002 with its entry context intact
            assertThat(batchHandler.invocations()).containsExactly(
                    "process(id-001=fieldValue-1)",
                    "persist(id-001=fieldValue-1)");
            assertThat(eventListener.pointerCommits())
                    .containsExactly("lastEvent=/0#id-001 nextFetch=/0?after=id-001");
            assertThat(eventListener.failures()).singleElement().satisfies(failure -> {
                assertThat(failure.entryId()).isEqualTo("id-002");
                assertThat(failure.phase()).isEqualTo(FeedEntryPhase.DECODE);
            });
        }

        /**
         * The exceptional double failure: the wrap-up itself fails too. The processing failure is primary —
         * it concerns the oldest events, the ones a retry hits first — and the read failure travels along
         * as a suppressed exception. Nothing was committed.
         */
        @Test
        void aFailingWrapUpMakesTheProcessingFailurePrimaryWithTheReadFailureSuppressed() {
            batchHandler.failInPersist();
            FeedRuntime runtime = runtime(batchHandler, new FakeFeedHttpClient().head("/2")
                    .page("/0", resource("batchcap/0.json"))
                    .page("/2", resource("batchcap/2.json")),
                    feed -> feed.preferredProcessingSize(100));

            consume(runtime);

            assertThat(eventListener.pointerCommits()).isEmpty();
            assertThat(transactions.rollbacks()).isEqualTo(1);
            assertThat(eventListener.failures()).singleElement().satisfies(failure ->
                    assertThat(failure.cause().getSuppressed()).singleElement()
                            .isInstanceOf(AtomiumHttpException.class));
        }
    }

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
            // the failure carries the entry context: which entry, which phase
            assertThat(eventListener.failures()).singleElement().satisfies(failure -> {
                assertThat(failure.entryId()).isEqualTo("id-002");
                assertThat(failure.phase()).isEqualTo(FeedEntryPhase.DECODE);
            });
        }

        /**
         * A handler that throws (phase HANDLER): the transaction around the handler effect + the pointer write
         * rolls back, so the pointer stays at the previous entry and id-002 is delivered again on the next run.
         * Also, no commit is reported — the events follow the commit, not the attempt.
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
                            "feedPointerAdvanced(lastEvent=/0#id-001 nextFetch=/0?after=id-001)",
                            "runFailed(1)");
            // and the failed commit went through the transaction layer as a rollback, with the entry context
            assertThat(transactions.rollbacks()).isEqualTo(1);
            assertThat(eventListener.failures()).singleElement().satisfies(failure -> {
                assertThat(failure.entryId()).isEqualTo("id-002");
                assertThat(failure.phase()).isEqualTo(FeedEntryPhase.HANDLER);
            });
        }
    }

    /**
     * The counters. The framework counts {@code read} and {@code accepted} (it applies {@code accepts});
     * {@code processed} comes from the handler's {@link ProcessResult} — defaulting to the number of offered
     * entries when the handler reports nothing.
     */
    @Nested
    class Counters {

        /** The default: a handler that reports nothing gets processed = accepted. */
        @Test
        void withoutAReportedCountProcessedEqualsAccepted() {
            FeedRuntime runtime = batchRuntime(3);

            consume(runtime);

            assertThat(eventListener.events()).endsWith("runCompleted(read=7, accepted=7, processed=7)");
        }

        /** A deduplicating handler reports its own count through the {@link ProcessResult}. */
        @Test
        void aReportedProcessedCountEndsUpInTheRunResult() {
            FeedRuntime runtime = batchRuntime(3);
            batchHandler.dedupeOnAField();

            consume(runtime);

            // batches of 3, 3 and 1 offered entries deduplicate to 2, 3 and 1 processed
            assertThat(batchHandler.invocations()).contains(
                    "persist(id-003=alfa, id-002=beta)",
                    "persist(id-004=gamma, id-005=beta, id-006=alfa)",
                    "persist(id-007=delta)");
            assertThat(eventListener.events()).endsWith("runCompleted(read=7, accepted=7, processed=6)");
        }

        /**
         * Every commit carries the delta since the previous commit ({@code feedPointerAdvanced}), so metrics
         * already show progress during a long run. The deltas together = the run result.
         */
        @Test
        void everyCommitReportsTheDeltaSinceThePrevious() {
            FeedRuntime runtime = batchRuntime(3);
            batchHandler.dedupeOnAField();

            consume(runtime);

            assertThat(eventListener.commitDeltas()).containsExactly(
                    "read=3, accepted=3, processed=2",
                    "read=3, accepted=3, processed=3",
                    "read=1, accepted=1, processed=1");
        }

        /** An idle checkpoint (an entirely filtered-out stretch) reports zeros — but does advance the pointer. */
        @Test
        void anIdleCheckpointReportsZeros() {
            FeedRuntime runtime = batchRuntime(3);
            batchHandler.acceptOnly(content -> false);

            consume(runtime);

            assertThat(eventListener.commitDeltas()).containsExactly(
                    "read=6, accepted=0, processed=0",
                    "read=1, accepted=0, processed=0");
        }

        /**
         * Every commit carries the {@code updated} of the youngest entry it advanced the pointer past — the
         * freshness signal for metrics and health.
         */
        @Test
        void everyCommitCarriesTheUpdatedOfTheYoungestEntryItCovered() {
            FeedRuntime runtime = batchRuntime(3);

            consume(runtime);

            assertThat(eventListener.commitLatestEvents()).containsExactly(
                    "2026-01-31T12:03+01:00", "2026-01-31T12:06+01:00", "2026-01-31T12:07+01:00");
        }

        /** A checkpoint that covered no entries (an empty page) carries no freshness timestamp. */
        @Test
        void aCheckpointThatCoveredNoEntriesCarriesNoTimestamp() {
            FeedRuntime runtime = runtime(handler, new FakeFeedHttpClient().head("/2")
                    .page("/0", resource("empty-page/0.json"))
                    .page("/1", resource("empty-page/1.json"))   // empty
                    .page("/2", resource("empty-page/2.json")));

            consume(runtime);

            assertThat(eventListener.commitLatestEvents()).containsExactly(
                    "2026-01-31T12:01+01:00", "2026-01-31T12:02+01:00", "2026-01-31T12:03+01:00",
                    "-",
                    "2026-01-31T12:04+01:00", "2026-01-31T12:05+01:00");
        }

        /**
         * An idle checkpoint over a filtered-out stretch <em>does</em> carry the {@code updated} of the
         * filtered entries: freshness reports what the pointer covers, not what was processed.
         */
        @Test
        void anIdleCheckpointCarriesTheUpdatedOfTheFilteredEntries() {
            FeedRuntime runtime = batchRuntime(3);
            batchHandler.acceptOnly(content -> false);

            consume(runtime);

            assertThat(eventListener.commitLatestEvents()).containsExactly(
                    "2026-01-31T12:06+01:00", "2026-01-31T12:07+01:00");
        }

        /**
         * {@code processed} is a free measure of realised work: a handler may count something other than
         * entries (say the business entities a batch upserts), so it may also exceed {@code accepted}.
         */
        @Test
        void theProcessedCounterIsAFreeMeasureAndMayExceedAccepted() {
            SimpleBatchedProcessingFeedHandler<TestFeedEntry, String> countingEntities =
                    new SimpleBatchedProcessingFeedHandler<>() {
                        @Override
                        public String getFeedId() {
                            return "feed";
                        }

                        @Override
                        public ProcessResult<String> process(List<ProcessingEntry<TestFeedEntry>> entries) {
                            // as if every entry carried two entities to upsert
                            return ProcessResult.of("prepared", entries.size() * 2);
                        }

                        @Override
                        public void persist(String prepared) {
                        }
                    };
            FeedRuntime runtime = runtime(countingEntities, batchFeed(), feed -> feed.preferredProcessingSize(3));

            consume(runtime);

            assertThat(eventListener.events()).endsWith("runCompleted(read=7, accepted=7, processed=14)");
        }
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

    /** The batch feed: page /0 with recurring aFields (alfa/beta/gamma) and the head /1 (delta). */
    private static FakeFeedHttpClient batchFeed() {
        return new FakeFeedHttpClient().head("/1")
                .page("/0", resource("batch/0.json"))
                .page("/1", resource("batch/1.json"));
    }

    /** The safety-net feed: three pages (/0, /1 and the head /2), five entries, all distinct. */
    private static FakeFeedHttpClient safetyNetFeed() {
        return new FakeFeedHttpClient().head("/2")
                .page("/0", resource("batchcap/0.json"))
                .page("/1", resource("batchcap/1.json"))
                .page("/2", resource("batchcap/2.json"));
    }

    private FeedRuntime batchRuntime(int preferredProcessingSize) {
        return runtime(batchHandler, batchFeed(), feed -> feed.preferredProcessingSize(preferredProcessingSize));
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

    /** One synchronous run of the real read loop on a scripted processor behind the seam. */
    private void scriptedRun(Supplier<FeedProcessor<TestFeedEntry>> processors, FakeFeedHttpClient source,
                             int maxUncommittedPages) {
        scriptedConsumer(processors, source, maxUncommittedPages).run(() -> false);
    }

    /**
     * A directly constructed consumer on a scripted processor — for seam behavior the two real processors
     * never show. Everything else is real: the fetch API, the pointer bookkeeping, the transactions and the
     * listeners.
     */
    private FeedConsumerImpl<TestFeedEntry> scriptedConsumer(Supplier<FeedProcessor<TestFeedEntry>> processors,
                                                             FakeFeedHttpClient source, int maxUncommittedPages) {
        AtomiumClient atomiumClient = new AtomiumClient(source, new JacksonFeedPageDecoder());
        return new FeedConsumerImpl<>("feed", handler, processors, maxUncommittedPages, atomiumClient,
                CONTENT_DECODER, new InMemoryFeedPointerRepository(), transactions,
                atomiumClient::pointerToOldest, new FeedEventListeners(List.of(eventListener)));
    }

    /** Captures the consumer's own framework logging (the safety-net decline warning). */
    private static ListAppender<ILoggingEvent> captureConsumerLog() {
        Logger logger = (Logger) LoggerFactory.getLogger(FeedConsumerImpl.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
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
