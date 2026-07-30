package be.wegenenverkeer.atomium.client.fetch;

import be.wegenenverkeer.atomium.client.exception.AtomiumHttpException;
import be.wegenenverkeer.atomium.client.exception.AtomiumInvalidPageException;
import be.wegenenverkeer.atomium.client.exception.AtomiumPageGoneException;
import be.wegenenverkeer.atomium.client.port.FeedHttpClient;
import be.wegenenverkeer.atomium.client.protocol.FeedPageRel;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AtomiumClientTest {

    /**
     * Scenario tests illustrating typical usage. They work with explicit JSON pages from
     * {@code src/test/resources/feedpages/} (one file per page/version), to keep the illustration as
     * concrete as possible.
     *
     * <p>The feed consists of three pages: {@code /0} (oldest, complete), {@code /1} (middle, complete)
     * and {@code /2} (youngest, incomplete). The relative links contain <em>no</em> pagesize.
     */
    @Nested
    class Scenarios {

        @Test
        void fetchFromOldest() {
            var rest = completeFeed();
            var client = client(rest);

            // start at the oldest page and follow the feed up to the live head
            var collected = new ArrayList<String>();
            FeedPointer pointer = client.pointerToOldest();
            while (true) {
                FetchResult result = client.fetch(pointer).orElseThrow();
                result.fetchEntries().forEach(fetchEntry -> collected.add(fetchEntry.entry().id()));
                pointer = result.nextFeedPointer();
                if (!result.feedHasMorePages()) {
                    break;
                }
            }

            assertThat(collected).containsExactly(
                    "id-001", "id-002", "id-003", "id-004", "id-005", "id-006", "id-007", "id-008");
        }

        @Test
        void fetchYoungest() {
            var client = client(completeFeed());

            FetchResult result = client.fetchYoungest();

            assertThat(ids(result)).containsExactly("id-007", "id-008"); // current entries of the head
            assertThat(result.feedHasMorePages()).isFalse();
            assertThat(result.nextFeedPointer().nextFetch().pageLink()).isEqualTo("/2");
        }

        @Test
        void fetchFromPage() {
            var rest = new FakeFeedHttpClient().page("/1", resource("1.json"));

            FetchResult result = client(rest).fetch(new FeedPointer("/1")).orElseThrow();

            assertThat(ids(result)).containsExactly("id-004", "id-005", "id-006");
            assertThat(result.nextFeedPointer().nextFetch().pageLink()).isEqualTo("/2"); // the younger page
        }

        @Test
        void fetchFromNowWithPolling_whenServerWithEtagSupport() {
            var rest = new FakeFeedHttpClient().head("/2").page("/2", resource("2-v1.json"), "etag-v1");
            var client = client(rest);

            // 'from now' skips the existing entries (id-007, id-008)
            FeedPointer pointer = client.pointerFromNow();
            assertThat(pointer).isEqualTo(onPage("/2", "id-008", "etag-v1"));

            // nothing new: the server (with etag) responds with 304
            assertThat(client.fetch(pointer)).isEmpty();

            // a new event arrives (new etag): only id-009 is delivered
            rest.page("/2", resource("2-v2.json"), "etag-v2");
            assertThat(ids(client.fetch(pointer).orElseThrow())).containsExactly("id-009");
        }

        @Test
        void fetchFromNowWithPolling_whenServerWithoutEtagSupport() {
            var rest = new FakeFeedHttpClient().head("/2").page("/2", resource("2-v1.json")); // no etag
            var client = client(rest);

            FeedPointer pointer = client.pointerFromNow();
            assertThat(pointer).isEqualTo(onPage("/2", "id-008", null));

            // nothing new: without etag we get a 200, but id-007/id-008 are filtered out → empty
            assertThat(client.fetch(pointer).orElseThrow().fetchEntries()).isEmpty();

            // a new event arrives: only id-009 is delivered
            rest.page("/2", resource("2-v2.json"));
            assertThat(ids(client.fetch(pointer).orElseThrow())).containsExactly("id-009");
        }

        @Test
        void processPartOfPage_laterFetchRestOfPage() {
            var rest = new FakeFeedHttpClient().page("/1", resource("1.json"));
            var client = client(rest);
            var processed = new ArrayList<String>();

            // first fetch: process the first two entries, then a 'failure' occurs
            FetchResult first = client.fetch(new FeedPointer("/1")).orElseThrow();
            FeedPointer afterLastProcessed = new FeedPointer("/1"); // placeholder, set below
            for (int i = 0; i < 2; i++) {
                FetchEntry fetchEntry = first.fetchEntries().get(i);
                processed.add(fetchEntry.entry().id());
                afterLastProcessed = fetchEntry.nextFeedPointer(); // the position advances per processed event
            }
            // ... crash; later we resume from the saved pointer (after id-005)
            assertThat(afterLastProcessed).isEqualTo(onPage("/1", "id-005", null));

            FetchResult resumed = client.fetch(afterLastProcessed).orElseThrow();
            resumed.fetchEntries().forEach(fetchEntry -> processed.add(fetchEntry.entry().id()));

            // every event processed exactly once, no double processing of id-004/id-005
            assertThat(processed).containsExactly("id-004", "id-005", "id-006");
        }

        private FakeFeedHttpClient completeFeed() {
            return new FakeFeedHttpClient().head("/2")
                    .page("/0", resource("0.json"))
                    .page("/1", resource("1.json"))
                    .page("/2", resource("2-v1.json"));
        }
    }

    @Nested
    class ReversalAndDelivery {

        @Test
        void deliversEntriesOldestFirstWhenHeadFetched() {
            // the JSON is youngest-first; the client delivers oldest-first
            var rest = new FakeFeedHttpClient().head("/2")
                    .page("/2", FeedFixture.page("/2").oldest("/0").older("/1")
                            .entry("id-009", "2026-01-31T12:09:00+01:00", "fieldValue-9")
                            .entry("id-008", "2026-01-31T12:08:00+01:00", "fieldValue-8")
                            .entry("id-007", "2026-01-31T12:07:00+01:00", "fieldValue-7")
                            .json());

            FetchResult result = client(rest).fetchYoungest();

            assertThat(ids(result)).containsExactly("id-007", "id-008", "id-009");
            assertThat(result.feedPageMetadata().href(FeedPageRel.SELF)).isEqualTo("/2");
            assertThat(result.feedPageMetadata().href(FeedPageRel.OLDEST)).contains("/0");
        }

        @Test
        void keepsContentValueAsRawJson() {
            var rest = new FakeFeedHttpClient().head("/0")
                    .page("/0", FeedFixture.page("/0").oldest("/0")
                            .entry("id-001", "2026-01-31T12:01:00+01:00", "fieldValue-1")
                            .json());

            FetchResult result = client(rest).fetchYoungest();

            assertThat(result.fetchEntries().getFirst().entry().content().value())
                    .isEqualTo("{\"aField\":\"fieldValue-1\"}");
        }
    }

    @Nested
    class NextFeedPointerCalculation {

        @Test
        void pointsNextPointerToYoungerPageWhenPageComplete() {
            // /0 is the oldest page AND complete (has a 'previous' link to the younger /1)
            var rest = new FakeFeedHttpClient()
                    .page("/0", FeedFixture.page("/0").oldest("/0").younger("/1")
                            .entry("id-003", "2026-01-31T12:03:00+01:00", "fieldValue-3")
                            .entry("id-002", "2026-01-31T12:02:00+01:00", "fieldValue-2")
                            .entry("id-001", "2026-01-31T12:01:00+01:00", "fieldValue-1")
                            .json(), "etag-0");

            FetchResult result = client(rest).fetch(new FeedPointer("/0")).orElseThrow();

            assertThat(ids(result)).containsExactly("id-001", "id-002", "id-003");
            assertThat(result.feedHasMorePages()).isTrue();
            assertThat(result.nextFeedPointer())
                    .isEqualTo(afterJumpTo("/1", new EventCoordinate("/0", "id-003"))); // previous link, no etag
        }

        @Test
        void keepsNextPointerOnSelfWithYoungestIdWhenPageIncomplete() {
            var rest = new FakeFeedHttpClient()
                    .page("/2", FeedFixture.page("/2").oldest("/0").older("/1")
                            .entry("id-009", "2026-01-31T12:09:00+01:00", "fieldValue-9")
                            .entry("id-008", "2026-01-31T12:08:00+01:00", "fieldValue-8")
                            .json(), "etag-2");

            FetchResult result = client(rest).fetch(new FeedPointer("/2")).orElseThrow();

            assertThat(result.feedHasMorePages()).isFalse();
            assertThat(result.nextFeedPointer())
                    .isEqualTo(onPage("/2", "id-009", "etag-2")); // dedup from the youngest
        }

        @Test
        void keepsNextPointerOnSelfWhenPageEmpty() {
            var rest = new FakeFeedHttpClient()
                    .page("/2", FeedFixture.page("/2").oldest("/0").older("/1").json(), "etag-empty");

            FetchResult result = client(rest).fetch(new FeedPointer("/2")).orElseThrow();

            assertThat(result.fetchEntries()).isEmpty();
            assertThat(result.feedHasMorePages()).isFalse();
            assertThat(result.nextFeedPointer()).isEqualTo(onPage("/2", null, "etag-empty"));
        }

        @Test
        void followsTheYoungerPageWhenPageCompleteButEmpty() {
            // A complete page (with a younger link) can be empty, e.g. with a "filtered" feed.
            // It must not be polled endlessly: the pointer has to point to the younger page.
            var rest = new FakeFeedHttpClient()
                    .page("/1", FeedFixture.page("/1").oldest("/0").older("/0").younger("/2").json(), "etag-1");

            FetchResult result = client(rest).fetch(new FeedPointer("/1")).orElseThrow();

            assertThat(result.fetchEntries()).isEmpty();
            assertThat(result.feedHasMorePages()).isTrue();
            assertThat(result.nextFeedPointer()).isEqualTo(new FeedPointer("/2"));
        }
    }

    @Nested
    class PointerFactories {

        @Test
        void oldestPointsToLastLink() {
            var rest = new FakeFeedHttpClient().head("/2")
                    .page("/2", FeedFixture.page("/2").oldest("/0").older("/1")
                            .entry("id-009", "2026-01-31T12:09:00+01:00", "fieldValue-9").json());

            FeedPointer pointer = client(rest).pointerToOldest();

            assertThat(pointer).isEqualTo(new FeedPointer("/0"));
        }

        @Test
        void nowGivesPointerJustAfterTheYoungestEntry() {
            var rest = new FakeFeedHttpClient().head("/2")
                    .page("/2", FeedFixture.page("/2").oldest("/0").older("/1")
                            .entry("id-008", "2026-01-31T12:08:00+01:00", "fieldValue-8")
                            .entry("id-007", "2026-01-31T12:07:00+01:00", "fieldValue-7")
                            .json(), "etag-a");

            FeedPointer pointer = client(rest).pointerFromNow();

            assertThat(pointer).isEqualTo(onPage("/2", "id-008", "etag-a"));
        }
    }

    @Nested
    class FetchRawPage {

        @Test
        void returnsTheRawHttpResponseWithoutDecoding() {
            var rest = new FakeFeedHttpClient()
                    .head("/2")
                    .page("/2", "{\"raw\":\"json\"}", "etag-2");

            // empty string = head
            FeedHttpClient.HttpResponse head = client(rest).fetchRawPage("");
            assertThat(head.status()).isEqualTo(200);
            assertThat(head.body()).isEqualTo("{\"raw\":\"json\"}");
            assertThat(head.etag()).isEqualTo("etag-2");

            // a raw fetch must not send an If-None-Match (no dedup/304 in diagnostics)
            assertThat(rest.lastCall()).isEqualTo(new FakeFeedHttpClient.Call("", null));

            // an explicit page link
            assertThat(client(rest).fetchRawPage("/2").status()).isEqualTo(200);
            assertThat(rest.lastCall()).isEqualTo(new FakeFeedHttpClient.Call("/2", null));
        }
    }

    @Nested
    class Filtering {

        @Test
        void filtersAlreadyDeliveredEntriesWhenLastEventIdPresent() {
            var rest = new FakeFeedHttpClient().page("/1", completeMiddlePage(), "etag-1");

            FetchResult result = client(rest).fetch(onPage("/1", "id-005", null)).orElseThrow();

            assertThat(ids(result)).containsExactly("id-006");
        }

        @Test
        void filtersNothingWhenLastEventIdNotPresent() {
            var rest = new FakeFeedHttpClient().page("/1", completeMiddlePage(), "etag-1");

            FetchResult result = client(rest).fetch(onPage("/1", "id-999", null)).orElseThrow();

            assertThat(ids(result)).containsExactly("id-004", "id-005", "id-006");
        }

        @Test
        void deliversEmptyResultWhenNoNewFetchEntries() {
            // server without etag support, incomplete page polled again without new events
            var rest = new FakeFeedHttpClient()
                    .page("/2", FeedFixture.page("/2").oldest("/0").older("/1")
                            .entry("id-008", "2026-01-31T12:08:00+01:00", "fieldValue-8")
                            .entry("id-007", "2026-01-31T12:07:00+01:00", "fieldValue-7")
                            .json());

            FetchResult result = client(rest).fetch(onPage("/2", "id-008", null)).orElseThrow();

            assertThat(result.fetchEntries()).isEmpty();
            assertThat(result.feedHasMorePages()).isFalse();
            assertThat(result.nextFeedPointer()).isEqualTo(onPage("/2", "id-008", null));
        }
    }

    @Nested
    class Etags {

        @Test
        void returns304ForNotModifiedPageWhenServerSupportsEtags() {
            var rest = new FakeFeedHttpClient()
                    .page("/2", FeedFixture.page("/2").oldest("/0").older("/1")
                            .entry("id-008", "2026-01-31T12:08:00+01:00", "fieldValue-8").json(), "etag-x");

            Optional<FetchResult> result = client(rest).fetch(onPage("/2", "id-008", "etag-x"));

            assertThat(result).isEmpty();
        }

        @Test
        void sendsEtagAsIfNoneMatchWhenPresent() {
            var rest = new FakeFeedHttpClient()
                    .page("/2", FeedFixture.page("/2").oldest("/0").older("/1")
                            .entry("id-008", "2026-01-31T12:08:00+01:00", "fieldValue-8").json(), "etag-x");

            client(rest).fetch(onPage("/2", null, "etag-x"));

            assertThat(rest.lastCall()).isEqualTo(new FakeFeedHttpClient.Call("/2", "etag-x"));
        }
    }

    @Nested
    class PerEntryPointers {

        @Test
        void pointsItsPointerToSelfWithoutEtagWhenEntryNotLast() {
            var rest = new FakeFeedHttpClient().page("/1", completeMiddlePage(), "etag-1");

            FetchResult result = client(rest).fetch(new FeedPointer("/1")).orElseThrow();
            List<FetchEntry> entries = result.fetchEntries();

            // intermediate entries: self + id, deliberately WITHOUT etag (a 304 would hide the rest otherwise)
            assertThat(entries.get(0).nextFeedPointer()).isEqualTo(onPage("/1", "id-004", null));
            assertThat(entries.get(1).nextFeedPointer()).isEqualTo(onPage("/1", "id-005", null));
            // last entry: the etag-safe result pointer (here: previous link because the page is complete)
            assertThat(entries.get(2).nextFeedPointer()).isEqualTo(result.nextFeedPointer());
            assertThat(entries.get(2).nextFeedPointer()).isEqualTo(afterJumpTo("/2", new EventCoordinate("/1", "id-006")));
        }

        @Test
        void deliversRemainingEntriesWhenResumedHalfwayThroughAnIncompletePage() {
            var rest = new FakeFeedHttpClient()
                    .page("/2", FeedFixture.page("/2").oldest("/0").older("/1")
                            .entry("id-009", "2026-01-31T12:09:00+01:00", "fieldValue-9")
                            .entry("id-008", "2026-01-31T12:08:00+01:00", "fieldValue-8")
                            .entry("id-007", "2026-01-31T12:07:00+01:00", "fieldValue-7")
                            .json(), "etag-2");
            var client = client(rest);

            FetchResult first = client.fetch(new FeedPointer("/2")).orElseThrow();
            FeedPointer afterId007 = first.fetchEntries().getFirst().nextFeedPointer();

            FetchResult remainder = client.fetch(afterId007).orElseThrow();

            assertThat(ids(remainder)).containsExactly("id-008", "id-009");
        }

        @Test
        void forcesA200WithoutLossWhenResumedHalfwayThroughACompletePage() {
            // the page has an etag; still, an intermediate pointer must not provoke a 304
            var rest = new FakeFeedHttpClient().page("/1", completeMiddlePage(), "etag-1");
            var client = client(rest);

            FetchResult first = client.fetch(new FeedPointer("/1")).orElseThrow();
            FeedPointer afterId004 = first.fetchEntries().getFirst().nextFeedPointer();
            assertThat(afterId004.nextFetch().etag()).isNull();

            FetchResult remainder = client.fetch(afterId004).orElseThrow();

            assertThat(ids(remainder)).containsExactly("id-005", "id-006");
        }
    }

    @Nested
    class PageTransition {

        @Test
        void losesNothingAndDuplicatesNothingAcrossTheBoundaryWhenIncompletePageBecomesComplete() {
            var processed = new ArrayList<String>();

            // phase 1: /1 is the youngest, incomplete page with id-001, id-002
            var rest = new FakeFeedHttpClient().head("/1")
                    .page("/1", FeedFixture.page("/1").oldest("/0").older("/0")
                            .entry("id-002", "2026-01-31T12:02:00+01:00", "fieldValue-2")
                            .entry("id-001", "2026-01-31T12:01:00+01:00", "fieldValue-1")
                            .json(), "etag-a");
            var client = client(rest);

            FetchResult phase1 = client.fetch(new FeedPointer("/1")).orElseThrow();
            phase1.fetchEntries().forEach(fetchEntry -> processed.add(fetchEntry.entry().id()));
            assertThat(phase1.feedHasMorePages()).isFalse();
            FeedPointer pointer = phase1.nextFeedPointer();

            // phase 2: /1 fills up (id-003 added) and becomes complete (gets previous -> /2);
            //         /2 is the new, youngest page with id-004
            rest.page("/1", FeedFixture.page("/1").oldest("/0").older("/0").younger("/2")
                            .entry("id-003", "2026-01-31T12:03:00+01:00", "fieldValue-3")
                            .entry("id-002", "2026-01-31T12:02:00+01:00", "fieldValue-2")
                            .entry("id-001", "2026-01-31T12:01:00+01:00", "fieldValue-1")
                            .json(), "etag-b")
                    .page("/2", FeedFixture.page("/2").oldest("/0").older("/1")
                            .entry("id-004", "2026-01-31T12:04:00+01:00", "fieldValue-4")
                            .json(), "etag-c")
                    .head("/2");

            FetchResult transition = client.fetch(pointer).orElseThrow();
            transition.fetchEntries().forEach(fetchEntry -> processed.add(fetchEntry.entry().id()));
            assertThat(ids(transition)).containsExactly("id-003"); // only the rest of /1, no duplicates
            assertThat(transition.feedHasMorePages()).isTrue();

            FetchResult newPage = client.fetch(transition.nextFeedPointer()).orElseThrow();
            newPage.fetchEntries().forEach(fetchEntry -> processed.add(fetchEntry.entry().id()));

            assertThat(processed).containsExactly("id-001", "id-002", "id-003", "id-004");
        }
    }

    @Nested
    class UpdatedTimestamps {

        @Test
        void parsesUpdatedTimestampInUTC() {
            OffsetDateTime updated = updatedOfFirstEntry("2026-01-31T12:00:00Z");

            assertThat(updated).isEqualTo(OffsetDateTime.parse("2026-01-31T12:00:00Z"));
            assertThat(updated.getOffset()).isEqualTo(ZoneOffset.UTC);
        }

        @Test
        void parsesUpdatedTimestampInWinterTime() {
            OffsetDateTime updated = updatedOfFirstEntry("2026-01-31T12:00:00+01:00");

            assertThat(updated).isEqualTo(OffsetDateTime.parse("2026-01-31T12:00:00+01:00"));
            assertThat(updated.getOffset()).isEqualTo(ZoneOffset.ofHours(1));
        }

        @Test
        void parsesUpdatedTimestampInSummerTime() {
            OffsetDateTime updated = updatedOfFirstEntry("2026-07-01T12:00:00+02:00");

            assertThat(updated).isEqualTo(OffsetDateTime.parse("2026-07-01T12:00:00+02:00"));
            assertThat(updated.getOffset()).isEqualTo(ZoneOffset.ofHours(2));
        }

        @Test
        void parsesUpdatedTimestampWithSecondsAndMillis() {
            OffsetDateTime updated = updatedOfFirstEntry("2026-01-31T12:01:02.345+01:00");

            assertThat(updated).isEqualTo(OffsetDateTime.parse("2026-01-31T12:01:02.345+01:00"));
        }
    }

    @Nested
    class FailureCases {

        @Test
        void throwsParseExceptionOnInvalidJson() {
            var rest = new FakeFeedHttpClient().head("/0").page("/0", "{ this is not valid json");

            assertThatThrownBy(() -> client(rest).fetchYoungest())
                    .isInstanceOf(AtomiumInvalidPageException.class);
        }

        @Test
        void throwsParseExceptionWhenResponseBodyMissing() {
            var rest = new FakeFeedHttpClient().head("/0").pageWithoutBody("/0");

            assertThatThrownBy(() -> client(rest).fetchYoungest())
                    .isInstanceOf(AtomiumInvalidPageException.class)
                    .hasMessageContaining("empty response body");
        }

        @Test
        void wrapsUnexpectedMapperErrorAsParseException() {
            var rest = new FakeFeedHttpClient().head("/0").page("/0", "{}");
            var client = new AtomiumClient(rest, page -> {
                throw new IllegalStateException("boom");
            });

            assertThatThrownBy(client::fetchYoungest)
                    .isInstanceOf(AtomiumInvalidPageException.class)
                    .hasRootCauseInstanceOf(IllegalStateException.class);
        }

        @Test
        void throwsHttpExceptionOnUnexpectedStatus() {
            var rest = new FakeFeedHttpClient().status("/9", 500);

            assertThatThrownBy(() -> client(rest).fetch(new FeedPointer("/9")))
                    .isInstanceOf(AtomiumHttpException.class)
                    .satisfies(e -> assertThat(((AtomiumHttpException) e).status()).isEqualTo(500));
        }

        @Test
        void throwsPageGoneExceptionWhenPagePurged() {
            var rest = new FakeFeedHttpClient().status("/9", 410);

            assertThatThrownBy(() -> client(rest).fetch(new FeedPointer("/9")))
                    .isInstanceOf(AtomiumPageGoneException.class)
                    .hasMessageContaining("410");
        }
    }

    // === test-data helpers =======================================================================

    private static AtomiumClient client(FakeFeedHttpClient rest) {
        return new AtomiumClient(rest, new JacksonFeedPageDecoder());
    }

    private static List<String> ids(FetchResult result) {
        return result.fetchEntries().stream().map(fetchEntry -> fetchEntry.entry().id()).toList();
    }

    /** Pointer that keeps reading the SAME page: the last processed event is on the fetch page (filter == id). */
    private static FeedPointer onPage(String page, @Nullable String lastEventId, @Nullable String etag) {
        EventCoordinate lastEvent = lastEventId == null ? null : new EventCoordinate(page, lastEventId);
        return new FeedPointer(lastEvent, new FetchCoordinate(page, lastEventId, etag));
    }

    /** Pointer that has jumped to a YOUNGER page: fetch on that page (no filter/etag), lastEvent on the old one. */
    private static FeedPointer afterJumpTo(String youngerPage, EventCoordinate lastEvent) {
        return new FeedPointer(lastEvent, new FetchCoordinate(youngerPage, null, null));
    }

    /**
     * A complete middle page /1 with id-004..id-006 (previous -> /2).
     */
    private static String completeMiddlePage() {
        return FeedFixture.page("/1").oldest("/0").older("/0").younger("/2")
                .entry("id-006", "2026-01-31T12:06:00+01:00", "fieldValue-6")
                .entry("id-005", "2026-01-31T12:05:00+01:00", "fieldValue-5")
                .entry("id-004", "2026-01-31T12:04:00+01:00", "fieldValue-4")
                .json();
    }

    private static OffsetDateTime updatedOfFirstEntry(String updated) {
        String json = FeedFixture.page("/0").oldest("/0")
                .entry("id-001", updated, "fieldValue-1")
                .json();
        var rest = new FakeFeedHttpClient().head("/0").page("/0", json);

        return client(rest).fetchYoungest().fetchEntries().getFirst().entry().updated();
    }

    /**
     * Loads a feed page JSON from {@code src/test/resources/feedpages/}.
     */
    private static String resource(String file) {
        String path = "/feedpages/" + file;
        try (InputStream in = AtomiumClientTest.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("resource not found: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
