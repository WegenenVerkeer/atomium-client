package be.wegenenverkeer.atomium.client.springboot;

import be.wegenenverkeer.atomium.client.handler.BatchedFeedHandler;
import be.wegenenverkeer.atomium.client.handler.EntryFeedHandler;
import be.wegenenverkeer.atomium.client.handler.FeedEventListener;
import be.wegenenverkeer.atomium.client.handler.FeedHandler;
import be.wegenenverkeer.atomium.client.handler.FeedHandlerBatch;
import be.wegenenverkeer.atomium.client.handler.FeedRunner;
import be.wegenenverkeer.atomium.client.handler.InterruptingFeedEventListener;
import be.wegenenverkeer.atomium.client.handler.RecordingFeedEventListener;
import be.wegenenverkeer.atomium.client.handler.Feeds;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.concurrent.atomic.AtomicInteger;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Framework-level integration test: verifies that a {@link FeedHandler} receives the correct callbacks when the
 * feed consumer walks the (WireMock) feed, that the {@link FeedEventListener}s see the right event timeline, and that
 * the feedPointer is persisted at the right position at every commit point (postgres via testcontainers) so that a
 * second run resumes where the first one stopped. Runs with an inline executor so {@code tryToStart()} executes the
 * run synchronously and the test can assert right afterwards.
 *
 * <p>The pointer asserts go through {@code eventListener.pointerCommits()}: {@code feedPointerAdvanced} fires on every
 * commit and is guaranteed to fire only after the commit, so that list is exactly what a crash at any moment would leave behind.
 * The absence of a commit shows that a pointer did <em>not</em> advance.
 */
@Import(FeedConsumerWireMockTest.InlineExecutorConfig.class)
class FeedConsumerWireMockTest extends AbstractAtomiumFeedIT {

    /**
     * Test customizers: an <em>inline</em> executor ({@code Runnable::run}) so {@link FeedRunner#tryToStart()}
     * executes the run synchronously on the calling thread (deterministic asserts, no feed thread to wait for), plus a
     * counting interceptor on the HTTP client of {@code foo-app} — the end-to-end proof that a customizer reaches the
     * actually used client. Together they show that both executor and client builder are configurable via {@link FeedCustomizer}.
     */
    @TestConfiguration
    static class InlineExecutorConfig {

        static final AtomicInteger INTERCEPTOR_CALLS = new AtomicInteger();

        @Bean
        FeedCustomizer inlineExecutorCustomizer() {
            return feed -> feed.setExecutor(Runnable::run);
        }

        @Bean
        FeedCustomizer countingInterceptorCustomizer() {
            return FeedCustomizer.forFeed("foo-app", feed ->
                    feed.restClientBuilder().requestInterceptor((request, body, execution) -> {
                        INTERCEPTOR_CALLS.incrementAndGet();
                        return execution.execute(request, body);
                    }));
        }

        // app-wide FeedEventListener beans: added to every feed by the autoconfig
        @Bean
        RecordingFeedEventListener recordingFeedEventListener() {
            return new RecordingFeedEventListener();
        }

        @Bean
        InterruptingFeedEventListener interruptingFeedEventListener() {
            return new InterruptingFeedEventListener();
        }
    }

    @Autowired
    private FooAppFeedHandler handler;

    @Autowired
    private FooAppEmptyPageFeedHandler emptyPageHandler;

    @Autowired
    private FooAppBatchFeedHandler batchHandler;

    @Autowired
    private FooAppBatchCapFeedHandler batchCapHandler;

    @Autowired
    private RecordingFeedEventListener eventListener;

    @Autowired
    private InterruptingFeedEventListener interruptingListener;

    @Autowired
    private Feeds feeds;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void reset() {
        wiremock.resetAll();
        handler.reset();
        emptyPageHandler.reset();
        batchHandler.reset();
        batchCapHandler.reset();
        eventListener.reset();
        interruptingListener.reset();
        InlineExecutorConfig.INTERCEPTOR_CALLS.set(0);
        jdbcClient.sql("TRUNCATE atomium_feed_pointer_v1").update();
    }

    /** Deactivates the feed (as an admin or a shutdown would) → the ongoing run sees {@code isInterrupted}. */
    private Runnable deactivate(String feedId) {
        return () -> feeds.get(feedId).runner().deactivate();
    }

    /** The complete feed (pages /0, /1 and the head /2) with 8 entries. */
    private void stubCompleteFeed() {
        wiremock.stubFor(get(urlPathEqualTo("/feed")).willReturn(okJson(resource("2-v1.json"))));   // head
        wiremock.stubFor(get(urlPathEqualTo("/feed/0")).willReturn(okJson(resource("0.json"))));
        wiremock.stubFor(get(urlPathEqualTo("/feed/1")).willReturn(okJson(resource("1.json"))));
        wiremock.stubFor(get(urlPathEqualTo("/feed/2")).willReturn(okJson(resource("2-v1.json"))));
    }

    /** The batch feed: page /0 with duplicates (alfa/beta/gamma) and the head /1 (delta). */
    private void stubBatchFeed() {
        wiremock.stubFor(get(urlPathEqualTo("/feed")).willReturn(okJson(resource("batch/1.json"))));   // head
        wiremock.stubFor(get(urlPathEqualTo("/feed/0")).willReturn(okJson(resource("batch/0.json"))));
        wiremock.stubFor(get(urlPathEqualTo("/feed/1")).willReturn(okJson(resource("batch/1.json"))));
    }

    /** The safety-net feed: three pages (/0, /1 and the head /2), five entries, all distinct. */
    private void stubBatchCapFeed() {
        wiremock.stubFor(get(urlPathEqualTo("/feed")).willReturn(okJson(resource("batchcap/2.json"))));   // head
        wiremock.stubFor(get(urlPathEqualTo("/feed/0")).willReturn(okJson(resource("batchcap/0.json"))));
        wiremock.stubFor(get(urlPathEqualTo("/feed/1")).willReturn(okJson(resource("batchcap/1.json"))));
        wiremock.stubFor(get(urlPathEqualTo("/feed/2")).willReturn(okJson(resource("batchcap/2.json"))));
    }

    /**
     * The handler SPI: an {@link be.wegenenverkeer.atomium.client.handler.EntryFeedHandler} receives the entries
     * one by one, in read order. The lifecycle (page boundary, end of feed) is no longer a handler callback — you
     * observe it via the {@link FeedEventListener} (see {@link Events}).
     */
    @Nested
    class EntryFeedHandler {

        @Test
        void receivesTheEntriesOneByOneInReadOrder() {
            stubCompleteFeed();

            consume(handler.getFeedId());

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
            stubCompleteFeed();

            consume(handler.getFeedId());

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
        }

        /**
         * A complete, <em>empty</em> middle page (e.g. with a "filtered" feed): the consumer must navigate through
         * it instead of refetching it forever — to the handler it simply does not exist.
         */
        @Test
        void skipsACompleteEmptyMiddlePage() {
            wiremock.stubFor(get(urlPathEqualTo("/feed")).willReturn(okJson(resource("empty-page/2.json"))));   // head
            wiremock.stubFor(get(urlPathEqualTo("/feed/0")).willReturn(okJson(resource("empty-page/0.json"))));
            wiremock.stubFor(get(urlPathEqualTo("/feed/1")).willReturn(okJson(resource("empty-page/1.json")))); // empty
            wiremock.stubFor(get(urlPathEqualTo("/feed/2")).willReturn(okJson(resource("empty-page/2.json"))));

            consume(emptyPageHandler.getFeedId());

            assertThat(emptyPageHandler.invocations()).containsExactly(
                    "onEntry(/0, id-001, fieldValue-1)",
                    "onEntry(/0, id-002, fieldValue-2)",
                    "onEntry(/0, id-003, fieldValue-3)",
                    "onEntry(/2, id-004, fieldValue-4)",
                    "onEntry(/2, id-005, fieldValue-5)");

            // the empty page yields no entry, but does yield a checkpoint (otherwise every poll would fetch it again)
            assertThat(eventListener.events()).containsSequence(
                    "pageFetched(/1, 0)",
                    "feedPointerAdvanced(lastEvent=/0#id-003 nextFetch=/2)",
                    "pageProcessed(/1)");
        }
    }

    /** The {@link FeedEventListener} SPI: the complete timeline of a run. */
    @Nested
    class Events {

        @Test
        void emitsTheCompleteEventSequence() {
            stubCompleteFeed();

            consume(handler.getFeedId());

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
            stubCompleteFeed();
            consume(handler.getFeedId());   // run 1 succeeds → the pointer is in the DB
            eventListener.reset();

            // the source now serves garbage
            wiremock.resetAll();
            //noinspection JsonStandardCompliance
            wiremock.stubFor(get(urlPathEqualTo("/feed/2")).willReturn(okJson("this is not a valid feed page")));

            consume(handler.getFeedId());

            // the failure occurs while fetching/decoding the page (no entry context) → runStarted + runFailed(1)
            assertThat(eventListener.events()).containsExactly("runStarted", "runFailed(1)");
        }

        /**
         * Edge case: the start position of a <em>brand-new</em> feed is determined lazily (an HTTP call to the source,
         * see {@code initial-feed-pointer}). If that call fails, the run never really began: there is no start position
         * to fill {@code runStarted} with, so only {@code runFailed} follows. Once a pointer is persisted, this can
         * no longer happen.
         */
        @Test
        void whenEvenTheStartPositionCannotBeDeterminedOnlyRunFailedFollows() {
            //noinspection JsonStandardCompliance
            wiremock.stubFor(get(urlPathEqualTo("/feed")).willReturn(okJson("this is not a valid feed page")));

            consume(handler.getFeedId());

            assertThat(eventListener.events()).containsExactly("runFailed(1)");
        }
    }

    /**
     * The {@link BatchedFeedHandler} variant: entries are buffered in a {@link FeedHandlerBatch}, deduplicated,
     * and only processed in one go at the threshold (or at the end of the feed) — together with the feed pointer, in
     * one transaction.
     *
     * <p>The batch feed ({@code foo-app-batch}) deliberately carries duplicates. Page {@code /0}, in read order:
     * id-001=alfa, id-002=beta, id-003=alfa, id-004=gamma, id-005=beta, id-006=alfa; the head {@code /1}: id-007=delta.
     * Deduplication is on {@code aField}, with threshold 3 from the config.
     */
    @Nested
    class BatchedFeedHandler {

        /**
         * With threshold 3 (three <em>distinct</em> values) the first batch flushes at id-004: alfa has been seen
         * three times by then but counts once, and the <em>last</em> alfa entry survives (id-003, not id-001). The
         * order is that of first appearance (alfa, beta, gamma).
         */
        @Test
        void flushesAtTheThresholdAndDeduplicatesLastWins() {
            stubBatchFeed();

            consume(batchHandler.getFeedId());

            assertThat(batchHandler.invocations()).containsExactly(
                    "onBatch(id-003=alfa, id-002=beta, id-004=gamma)",
                    "onBatch(id-005=beta, id-006=alfa, id-007=delta)");
        }

        /**
         * The core of the model: as long as the batch is not flushed, there is <em>no</em> commit — not even on a
         * page boundary. So there are exactly two commits, each up to and including the entry that triggered the flush.
         * A crash then repeats the entire uncommitted batch, which is the intent.
         */
        @Test
        void pinsThePointerWhileTheBatchIsNotFlushed() {
            stubBatchFeed();

            consume(batchHandler.getFeedId());

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
            stubBatchFeed();

            consume(batchHandler.getFeedId());

            assertThat(eventListener.events()).endsWith("runCompleted(read=7, accepted=7, processed=6)");
        }

        /**
         * A feed on which all events are irrelevant ({@code accepts} → {@code false}): the handler is never
         * invoked, but the pointer <em>does</em> advance — on the page boundaries. Without that checkpoint every poll
         * would fetch the whole irrelevant tail again; that is the reason it exists.
         */
        @Test
        void filteredOutEntriesDoNotReachTheHandlerButAdvanceThePointer() {
            stubBatchFeed();
            batchHandler.acceptOnly(content -> false);

            consume(batchHandler.getFeedId());

            assertThat(batchHandler.invocations()).isEmpty();
            assertThat(eventListener.events()).doesNotContain("entriesProcessed()");
            // only checkpoints on the page boundaries — deliberately none in the middle of a page
            assertThat(eventListener.pointerCommits()).containsExactly(
                    "lastEvent=/0#id-006 nextFetch=/1",
                    "lastEvent=/1#id-007 nextFetch=/1?after=id-007");
            assertThat(eventListener.events()).endsWith("runCompleted(read=7, accepted=0, processed=0)");
        }

        /**
         * Filtering and deduplication combined: only the alfa events count (3 of 7), and of those one survives
         * (id-006). So the threshold of 3 is never reached — but at the end of the feed the incomplete batch is
         * flushed anyway. A batch does not survive polls.
         */
        @Test
        void anIncompleteBatchIsStillFlushedAtTheEndOfTheFeed() {
            stubBatchFeed();
            batchHandler.acceptOnly(content -> "alfa".equals(content.aField()));

            consume(batchHandler.getFeedId());

            assertThat(batchHandler.invocations()).containsExactly("onBatch(id-006=alfa)");
            // one single commit, at the very end: until then the pointer stayed at the start position
            assertThat(eventListener.pointerCommits()).containsExactly(
                    "lastEvent=/1#id-007 nextFetch=/1?after=id-007");
            assertThat(eventListener.events()).endsWith(
                    "entriesProcessed(id-006)",
                    "feedPointerAdvanced(lastEvent=/1#id-007 nextFetch=/1?after=id-007)",
                    "endOfFeedReached",
                    "runCompleted(read=7, accepted=3, processed=1)");
        }
    }

    /**
     * The <b>page safety net</b> ({@code batch.max-unflushed-pages}). A heavily filtering or deduplicating feed
     * rarely reaches its threshold — and as long as there is no flush, the feed pointer does not advance. Without a
     * safety net the window a crash has to re-read thus grows without bound.
     *
     * <p>The feed {@code foo-app-batch-cap} isolates exactly that: threshold 100 (never reached) and
     * {@code max-unflushed-pages: 2}, across three pages (id-001..id-005, all distinct).
     */
    @Nested
    class SafetyNet {

        @Test
        void forcesAFlushAfterMaxUnflushedPages() {
            stubBatchCapFeed();

            consume(batchCapHandler.getFeedId());

            // the first batch spans two pages and is flushed by the safety net (not by the threshold);
            // the rest follows at the end of the feed
            assertThat(batchCapHandler.invocations()).containsExactly(
                    "onBatch(id-001=a, id-002=b, id-003=c, id-004=d)",
                    "onBatch(id-005=e)");
        }

        /** And so: the uncommitted window stays bounded to two pages. */
        @Test
        void boundsTheUncommittedWindow() {
            stubBatchCapFeed();

            consume(batchCapHandler.getFeedId());

            assertThat(eventListener.pointerCommits()).containsExactly(
                    "lastEvent=/1#id-004 nextFetch=/2",     // ← the safety net, after two pages
                    "lastEvent=/2#id-005 nextFetch=/2?after=id-005");

            // page /0 alone yields no commit: the page counter only reaches the threshold at the second page boundary
            assertThat(eventListener.events()).containsSequence(
                    "pageFetched(/0, 2)",
                    "pageProcessed(/0)",
                    "pageFetched(/1, 2)",
                    "entriesProcessed(id-001, id-002, id-003, id-004)");
        }
    }

    /**
     * A clean interruption (deactivation/shutdown) in the middle of a run: the consumer stops after the next
     * commit point. The contract is that nothing is lost and nothing is duplicated — the next run resumes exactly
     * where this one stopped. The {@link InterruptingFeedEventListener} forces the interruption point.
     */
    @Nested
    class Interruption {

        /** Interrupted in the middle of a page: the pointer sits at the last committed entry, no further. */
        @Test
        void inTheMiddleOfAPageStopsAfterTheLastCommittedEntry() {
            stubCompleteFeed();
            interruptingListener.interruptAfter("entriesProcessed(id-002)", deactivate(handler.getFeedId()));

            consume(handler.getFeedId());

            assertThat(handler.invocations()).containsExactly(
                    "onEntry(/0, id-001, fieldValue-1)",
                    "onEntry(/0, id-002, fieldValue-2)");
            // an interrupted run ends with runInterrupted (no runCompleted after it — that is only the normal ending)
            assertThat(eventListener.events()).endsWith(
                    "runInterrupted(read=2, accepted=2, processed=2)");
            assertThat(eventListener.pointerCommits()).last()
                    .isEqualTo("lastEvent=/0#id-002 nextFetch=/0?after=id-002");

            // and the next run resumes seamlessly: id-003 (nothing lost, nothing duplicated)
            handler.reset();
            eventListener.reset();

            consume(handler.getFeedId());

            assertThat(handler.invocations()).startsWith("onEntry(/0, id-003, fieldValue-3)");
        }

        /** Interrupted on a page boundary: the pointer is checkpointed at the next page. */
        @Test
        void onAPageBoundaryStopsAfterTheCheckpoint() {
            stubCompleteFeed();
            interruptingListener.interruptAfter("pageProcessed(/0)", deactivate(handler.getFeedId()));

            consume(handler.getFeedId());

            assertThat(eventListener.events()).endsWith(
                    "pageProcessed(/0)",
                    "runInterrupted(read=3, accepted=3, processed=3)");
            assertThat(eventListener.pointerCommits()).last().isEqualTo("lastEvent=/0#id-003 nextFetch=/1");

            // the next run starts right at page /1
            handler.reset();

            consume(handler.getFeedId());

            assertThat(handler.invocations()).startsWith("onEntry(/1, id-004, fieldValue-4)");
        }
    }

    /**
     * The failure paths. The stakes are the same every time and are the core of the transaction model: if an entry
     * does not get processed, the feed pointer must <em>not</em> advance past that entry — otherwise it would be
     * skipped silently.
     */
    @Nested
    class FailurePaths {

        /** An entry that cannot be decoded (phase DECODE): the run fails, the pointer stays at the previous entry. */
        @Test
        void anUnreadableEntryLeavesThePointerInPlace() {
            wiremock.stubFor(get(urlPathEqualTo("/feed")).willReturn(okJson(resource("2-v1.json"))));   // head
            wiremock.stubFor(get(urlPathEqualTo("/feed/0")).willReturn(okJson(resource("0-broken-entry.json"))));

            consume(handler.getFeedId());

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
            stubCompleteFeed();
            handler.failAt("id-002");

            consume(handler.getFeedId());

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
        }
    }

    /**
     * A poll on a not-modified head: the server answers {@code 304 Not Modified} (based on the etag the consumer
     * carries in its pointer). The run delivers nothing and leaves the pointer alone — and reports that as
     * {@code feedNotModified}, not as {@code endOfFeedReached} (for which we <em>did</em> fetch the head).
     */
    @Test
    void aPollOnANotModifiedFeedYieldsNothing() {
        wiremock.stubFor(get(urlPathEqualTo("/feed")).willReturn(okJson(resource("2-v1.json"))));   // head
        wiremock.stubFor(get(urlPathEqualTo("/feed/0")).willReturn(okJson(resource("0.json"))));
        wiremock.stubFor(get(urlPathEqualTo("/feed/1")).willReturn(okJson(resource("1.json"))));
        // the head carries an etag, so the consumer keeps it in its pointer ...
        wiremock.stubFor(get(urlPathEqualTo("/feed/2"))
                .willReturn(okJson(resource("2-v1.json")).withHeader("ETag", "\"v1\"")));
        // ... and a conditional refetch (If-None-Match) yields a 304
        wiremock.stubFor(get(urlPathEqualTo("/feed/2"))
                .withHeader("If-None-Match", matching(".+"))
                .willReturn(aResponse().withStatus(304)));

        consume(handler.getFeedId());   // run 1: the complete feed

        handler.reset();
        eventListener.reset();

        consume(handler.getFeedId());   // run 2: the head is not modified → 304

        assertThat(handler.invocations()).isEmpty();
        assertThat(eventListener.pointerCommits()).isEmpty();   // no needless write
        assertThat(eventListener.events()).containsExactly(
                "runStarted", "feedNotModified", "runCompleted(read=0, accepted=0, processed=0)");
    }

    /**
     * Proves end-to-end that a {@link FeedCustomizer} augments the actually used HTTP client: the counting
     * interceptor (added via {@code feed.restClientBuilder()}) runs on every HTTP call of the run.
     */
    @Test
    void aFeedCustomizerReachesTheActuallyUsedRestClient() {
        stubCompleteFeed();

        consume(handler.getFeedId());

        // the interceptor ran (once per fetched page) and the regular processing stayed intact
        assertThat(InlineExecutorConfig.INTERCEPTOR_CALLS.get()).isGreaterThan(0);
        assertThat(eventListener.events()).contains("endOfFeedReached");
    }

    /**
     * Activates the feed and triggers one run. Thanks to the direct (inline) executor the run executes synchronously
     * on this thread, so we can assert right afterwards.
     */
    private void consume(String feedId) {
        FeedRunner runner = feeds.get(feedId).runner();
        runner.activate();
        assertThat(runner.tryToStart()).isTrue();
    }
}
