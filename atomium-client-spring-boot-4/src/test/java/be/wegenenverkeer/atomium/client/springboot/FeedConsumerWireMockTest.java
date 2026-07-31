package be.wegenenverkeer.atomium.client.springboot;

import be.wegenenverkeer.atomium.client.handler.FeedEventListener;
import be.wegenenverkeer.atomium.client.handler.FeedHandler;
import be.wegenenverkeer.atomium.client.handler.FeedRunner;
import be.wegenenverkeer.atomium.client.handler.InterruptingFeedEventListener;
import be.wegenenverkeer.atomium.client.handler.RecordingFeedEventListener;
import be.wegenenverkeer.atomium.client.handler.Feeds;

import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.concurrent.atomic.AtomicInteger;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
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
     * executes the run synchronously on the calling thread (deterministic asserts, no feed thread to wait for), plus
     * two interceptors on the HTTP client of {@code foo-app} — one counting (the end-to-end proof that a customizer
     * reaches the actually used client), one minting a fresh {@code Authorization} value per request (the proof that
     * auth is applied per request, see {@link #theRetryAfterAFailedRunCarriesAFreshlyMintedAuthorizationHeader()}).
     * Together they show that both executor and client builder are configurable via {@link FeedCustomizer}.
     */
    @TestConfiguration
    static class InlineExecutorConfig {

        static final AtomicInteger INTERCEPTOR_CALLS = new AtomicInteger();
        static final AtomicInteger TOKEN_MINTS = new AtomicInteger();

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

        @Bean
        FeedCustomizer authMintingInterceptorCustomizer() {
            return FeedCustomizer.forFeed("foo-app", feed ->
                    feed.restClientBuilder().requestInterceptor((request, body, execution) -> {
                        request.getHeaders().set(HttpHeaders.AUTHORIZATION, "token-" + TOKEN_MINTS.incrementAndGet());
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
        InlineExecutorConfig.TOKEN_MINTS.set(0);
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
     * The {@link be.wegenenverkeer.atomium.client.handler.SimpleBatchedProcessingFeedHandler} tier: accepted
     * entries are buffered up to the processing threshold and then processed in two phases — {@code process}
     * outside any transaction, {@code persist} inside the transaction that also advances the feed pointer.
     *
     * <p>The batch feed ({@code foo-app-batch}) deliberately carries recurring entities. Page {@code /0}, in read
     * order: id-001=alfa, id-002=beta, id-003=alfa, id-004=gamma, id-005=beta, id-006=alfa; the head {@code /1}:
     * id-007=delta. The handler deduplicates on {@code aField} in {@code process}; threshold 3 from the config.
     */
    @Nested
    class BatchTier {

        /**
         * Threshold 3: the framework offers every third accepted entry as a batch to {@code process}; the
         * handler's own dedup (last-wins per {@code aField}, first-seen order) shrinks what {@code persist}
         * receives. Each {@code persist} line echoes the prepared state its {@code process} returned — {@code P}
         * travels intact through the whole Boot stack.
         */
        @Test
        void processesAtTheThresholdAndTheHandlersDedupShrinksTheBatch() {
            stubBatchFeed();

            consume(batchHandler.getFeedId());

            assertThat(batchHandler.invocations()).containsExactly(
                    "process(id-001=alfa, id-002=beta, id-003=alfa)",
                    "persist(id-003=alfa, id-002=beta)",
                    "process(id-004=gamma, id-005=beta, id-006=alfa)",
                    "persist(id-004=gamma, id-005=beta, id-006=alfa)",
                    "process(id-007=delta)",
                    "persist(id-007=delta)");
        }

        /**
         * The core of the model: a commit happens only when a batch wraps up — mid-page at the entry pointer, at
         * the last entry of a page at the page pointer, and for the leftover partial batch at the end of the
         * feed. In between, the pointer stays pinned, so a crash repeats the uncommitted batch.
         */
        @Test
        void commitsOnlyWhenABatchWrapsUp() {
            stubBatchFeed();

            consume(batchHandler.getFeedId());

            assertThat(eventListener.pointerCommits()).containsExactly(
                    "lastEvent=/0#id-003 nextFetch=/0?after=id-003",
                    "lastEvent=/0#id-006 nextFetch=/1",
                    "lastEvent=/1#id-007 nextFetch=/1?after=id-007");
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
            // only checkpoints on the page boundaries — deliberately none in the middle of a page
            assertThat(eventListener.pointerCommits()).containsExactly(
                    "lastEvent=/0#id-006 nextFetch=/1",
                    "lastEvent=/1#id-007 nextFetch=/1?after=id-007");
            assertThat(eventListener.events()).endsWith("runCompleted(read=7, accepted=0, processed=0)");
        }

        /**
         * Filtering and the handler's dedup combined: only the beta events count (2 of 7), so the threshold of 3
         * is never reached — but at the end of the feed the partial batch is wrapped up anyway (a batch does not
         * survive polls), and the dedup leaves one processed entry.
         */
        @Test
        void aPartialBatchIsStillWrappedUpAtTheEndOfTheFeed() {
            stubBatchFeed();
            batchHandler.acceptOnly(content -> "beta".equals(content.aField()));

            consume(batchHandler.getFeedId());

            assertThat(batchHandler.invocations()).containsExactly(
                    "process(id-002=beta, id-005=beta)",
                    "persist(id-005=beta)");
            // one single commit, at the very end: until then the pointer stayed at the start position
            assertThat(eventListener.pointerCommits()).containsExactly(
                    "lastEvent=/1#id-007 nextFetch=/1?after=id-007");
            assertThat(eventListener.events()).endsWith(
                    "feedPointerAdvanced(lastEvent=/1#id-007 nextFetch=/1?after=id-007)",
                    "pageProcessed(/1)",
                    "endOfFeedReached",
                    "runCompleted(read=7, accepted=2, processed=1)");
        }
    }

    /**
     * The <b>page safety net</b> ({@code processing.max-uncommitted-pages}). A heavily filtering feed
     * rarely reaches its threshold — and as long as nothing is committed, the feed pointer does not advance. Without a
     * safety net the window a crash has to re-read thus grows without bound.
     *
     * <p>The feed {@code foo-app-batch-cap} isolates exactly that: threshold 100 (never reached) and
     * {@code max-uncommitted-pages: 2}, across three pages (id-001..id-005, all distinct).
     */
    @Nested
    class SafetyNet {

        @Test
        void forcesAWrapUpAfterMaxUncommittedPages() {
            stubBatchCapFeed();

            consume(batchCapHandler.getFeedId());

            // the first batch spans two pages and is wrapped up by the safety net (not by the threshold);
            // the rest follows at the end of the feed
            assertThat(batchCapHandler.invocations()).containsExactly(
                    "process(id-001=a, id-002=b, id-003=c, id-004=d)",
                    "persist(id-001=a, id-002=b, id-003=c, id-004=d)",
                    "process(id-005=e)",
                    "persist(id-005=e)");
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
                    "feedPointerAdvanced(lastEvent=/1#id-004 nextFetch=/2)");
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
            interruptingListener.interruptAfter("committed(id-002)", deactivate(handler.getFeedId()));

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
         * The batch tier, phase 1: a {@code process} that throws fails the run before any transaction opens.
         * Nothing is committed, and the retry re-reads from the pinned pointer and offers the <em>same</em>
         * batch again — the crash/retry contract of the two-phase model, end-to-end through the JDBC pointer.
         */
        @Test
        void aFailingProcessPinsThePointerAndTheRetryReoffersTheSameBatch() {
            stubBatchFeed();
            batchHandler.failInProcess();

            consume(batchHandler.getFeedId());

            assertThat(batchHandler.invocations()).isEmpty();
            assertThat(eventListener.pointerCommits()).isEmpty();
            assertThat(eventListener.events()).endsWith("runFailed(1)");

            // the source recovers → the retry starts over from the pinned pointer, with the identical batch
            batchHandler.recover();

            consume(batchHandler.getFeedId());

            assertThat(batchHandler.invocations()).startsWith(
                    "process(id-001=alfa, id-002=beta, id-003=alfa)",
                    "persist(id-003=alfa, id-002=beta)");
            assertThat(eventListener.events()).endsWith("runCompleted(read=7, accepted=7, processed=6)");
        }

        /**
         * The batch tier, phase 2: a {@code persist} that throws rolls back the transaction it shares with the
         * feed pointer write — the pointer in the database does not move, so the retry repeats {@code process}
         * <em>and</em> {@code persist} for the same batch. Nothing is lost, nothing is skipped.
         */
        @Test
        void aFailingPersistRollsBackAndTheRetryRepeatsTheBatch() {
            stubBatchFeed();
            batchHandler.failInPersist();

            consume(batchHandler.getFeedId());

            // process ran (outside the transaction), persist failed inside it → no commit at all
            assertThat(batchHandler.invocations())
                    .containsExactly("process(id-001=alfa, id-002=beta, id-003=alfa)");
            assertThat(eventListener.pointerCommits()).isEmpty();
            assertThat(eventListener.events()).endsWith("runFailed(1)");

            batchHandler.reset();   // also recovers: reset clears the deliberate failure
            eventListener.reset();

            consume(batchHandler.getFeedId());

            // the full feed again, from the start: the rollback left no trace of the failed attempt
            assertThat(batchHandler.invocations()).containsExactly(
                    "process(id-001=alfa, id-002=beta, id-003=alfa)",
                    "persist(id-003=alfa, id-002=beta)",
                    "process(id-004=gamma, id-005=beta, id-006=alfa)",
                    "persist(id-004=gamma, id-005=beta, id-006=alfa)",
                    "process(id-007=delta)",
                    "persist(id-007=delta)");
            assertThat(eventListener.events()).endsWith("runCompleted(read=7, accepted=7, processed=6)");
        }

        /**
         * A handler that throws (phase HANDLER): the transaction around the handler effect + the pointer write
         * rolls back, so the pointer stays at the previous entry and id-002 is delivered again on the next run.
         * Also, no commit is reported — the events follow the commit, not the attempt.
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
     * Auth (typically a JWT) is applied via a request interceptor and must be minted <em>per HTTP request</em>: the
     * run that retries after a failed run must carry a freshly minted header, never a value reused from the failed
     * attempt. (The predecessor of this suite froze the {@code Authorization} header of the first attempt in its
     * retry loop; during a long outage the token expired mid-loop and every retry after that was rejected with a 403
     * until the application was restarted.)
     */
    @Test
    void theRetryAfterAFailedRunCarriesAFreshlyMintedAuthorizationHeader() {
        // the source fails once on the head fetch, then recovers
        wiremock.stubFor(get(urlPathEqualTo("/feed")).inScenario("recovering source")
                .whenScenarioStateIs(Scenario.STARTED)
                .willSetStateTo("recovered")
                .willReturn(aResponse().withStatus(500)));
        wiremock.stubFor(get(urlPathEqualTo("/feed")).inScenario("recovering source")
                .whenScenarioStateIs("recovered")
                .willReturn(okJson(resource("2-v1.json"))));
        wiremock.stubFor(get(urlPathEqualTo("/feed/0")).willReturn(okJson(resource("0.json"))));
        wiremock.stubFor(get(urlPathEqualTo("/feed/1")).willReturn(okJson(resource("1.json"))));
        wiremock.stubFor(get(urlPathEqualTo("/feed/2")).willReturn(okJson(resource("2-v1.json"))));

        consume(handler.getFeedId());   // run 1: fails on the head fetch
        assertThat(eventListener.events()).endsWith("runFailed(1)");

        consume(handler.getFeedId());   // run 2: the retry succeeds

        // the failed attempt used token-1; the retry minted token-2 instead of reusing token-1
        wiremock.verify(getRequestedFor(urlPathEqualTo("/feed"))
                .withHeader(HttpHeaders.AUTHORIZATION, equalTo("token-1")));
        wiremock.verify(getRequestedFor(urlPathEqualTo("/feed"))
                .withHeader(HttpHeaders.AUTHORIZATION, equalTo("token-2")));
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
