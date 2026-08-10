package be.wegenenverkeer.atomium.client.spring;

import be.wegenenverkeer.atomium.client.fetch.AtomiumClient;
import be.wegenenverkeer.atomium.client.fetch.EventCoordinate;
import be.wegenenverkeer.atomium.client.fetch.FeedPointer;
import be.wegenenverkeer.atomium.client.fetch.FetchEntry;
import be.wegenenverkeer.atomium.client.fetch.FetchCoordinate;
import be.wegenenverkeer.atomium.client.fetch.FetchResult;
import org.jspecify.annotations.Nullable;
import be.wegenenverkeer.atomium.client.exception.AtomiumHttpException;
import be.wegenenverkeer.atomium.client.exception.AtomiumPageGoneException;
import be.wegenenverkeer.atomium.client.jackson.JacksonFeedPageDecoder;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test of {@link SpringFeedHttpClient} against a real (WireMock) HTTP server, through the
 * full stack: {@link AtomiumClient} + {@link SpringFeedHttpClient} + the real
 * {@link JacksonFeedPageDecoder}. The page bodies come from resource files.
 */
class SpringFeedHttpClientIT {

    @RegisterExtension
    static final WireMockExtension wm = WireMockExtension.newInstance()
            // gzip off: otherwise WireMock appends "--gzip" to the ETag, which breaks the etag round-trip
            .options(wireMockConfig().dynamicPort().gzipDisabled(true))
            .build();

    @BeforeEach
    void reset() {
        wm.resetAll();
    }

    /** The feed lives at {@code /feed}; page hrefs ({@code /0}, {@code /1}, {@code /2}) are appended. */
    private AtomiumClient client() {
        RestClient restClient = RestClient.builder().baseUrl(wm.baseUrl() + "/feed").build();
        return new AtomiumClient(new SpringFeedHttpClient(restClient), new JacksonFeedPageDecoder());
    }

    @Nested
    class Scenarios {

        @Test
        void fetchFromOldest() {
            wm.stubFor(get(urlPathEqualTo("/feed")).willReturn(okJson(resource("2-v1.json"))));
            wm.stubFor(get(urlPathEqualTo("/feed/0")).willReturn(okJson(resource("0.json"))));
            wm.stubFor(get(urlPathEqualTo("/feed/1")).willReturn(okJson(resource("1.json"))));
            wm.stubFor(get(urlPathEqualTo("/feed/2")).willReturn(okJson(resource("2-v1.json"))));
            var client = client();

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
            wm.stubFor(get(urlPathEqualTo("/feed")).willReturn(okJson(resource("2-v1.json"))));

            FetchResult result = client().fetchYoungest();

            assertThat(ids(result)).containsExactly("id-007", "id-008");
            assertThat(result.feedHasMorePages()).isFalse();
            assertThat(result.nextFeedPointer().nextFetch().pageLink()).isEqualTo("/2");
        }

        @Test
        void fetchFromPage() {
            wm.stubFor(get(urlPathEqualTo("/feed/1")).willReturn(okJson(resource("1.json"))));

            FetchResult result = client().fetch(new FeedPointer("/1")).orElseThrow();

            assertThat(ids(result)).containsExactly("id-004", "id-005", "id-006");
            assertThat(result.nextFeedPointer().nextFetch().pageLink()).isEqualTo("/2");
        }

        @Test
        void fetchFromNowWithPolling_whenServerSupportsEtags() {
            wm.stubFor(get(urlPathEqualTo("/feed")).willReturn(okJson(resource("2-v1.json")).withHeader("ETag", "etag-v1")));
            wm.stubFor(get(urlPathEqualTo("/feed/2")).withHeader("If-None-Match", equalTo("etag-v1"))
                    .willReturn(aResponse().withStatus(304)));
            var client = client();

            FeedPointer pointer = client.pointerFromNow();
            assertThat(pointer).isEqualTo(onPage("/2", "id-008", "etag-v1"));

            // nothing new -> 304
            assertThat(client.fetch(pointer)).isEmpty();

            // new event with a new etag -> only id-009
            wm.resetAll();
            wm.stubFor(get(urlPathEqualTo("/feed/2")).willReturn(okJson(resource("2-v2.json")).withHeader("ETag", "etag-v2")));
            assertThat(ids(client.fetch(pointer).orElseThrow())).containsExactly("id-009");
        }

        @Test
        void fetchFromNowWithPolling_whenServerDoesNotSupportEtags() {
            wm.stubFor(get(urlPathEqualTo("/feed")).willReturn(okJson(resource("2-v1.json"))));
            wm.stubFor(get(urlPathEqualTo("/feed/2")).willReturn(okJson(resource("2-v1.json"))));
            var client = client();

            FeedPointer pointer = client.pointerFromNow();
            assertThat(pointer).isEqualTo(onPage("/2", "id-008", null));

            // nothing new -> 200 but id-007/id-008 filtered out -> empty
            assertThat(client.fetch(pointer).orElseThrow().fetchEntries()).isEmpty();

            // new event -> only id-009
            wm.resetAll();
            wm.stubFor(get(urlPathEqualTo("/feed/2")).willReturn(okJson(resource("2-v2.json"))));
            assertThat(ids(client.fetch(pointer).orElseThrow())).containsExactly("id-009");
        }

        @Test
        void processesPartOfPageLaterFetchesRestOfPage() {
            wm.stubFor(get(urlPathEqualTo("/feed/1")).willReturn(okJson(resource("1.json"))));
            var client = client();
            var processed = new ArrayList<String>();

            FetchResult first = client.fetch(new FeedPointer("/1")).orElseThrow();
            FeedPointer afterLastProcessed = new FeedPointer("/1");
            for (int i = 0; i < 2; i++) {
                FetchEntry fetchEntry = first.fetchEntries().get(i);
                processed.add(fetchEntry.entry().id());
                afterLastProcessed = fetchEntry.nextFeedPointer();
            }
            // ... crash; later resume from the saved pointer (after id-005)
            assertThat(afterLastProcessed).isEqualTo(onPage("/1", "id-005", null));

            FetchResult resumed = client.fetch(afterLastProcessed).orElseThrow();
            resumed.fetchEntries().forEach(fetchEntry -> processed.add(fetchEntry.entry().id()));

            assertThat(processed).containsExactly("id-004", "id-005", "id-006");
        }
    }

    @Nested
    class QueryParams {

        /** Same feed, but the client is configured with default query params for every GET. */
        private AtomiumClient clientWithQueryParams() {
            RestClient restClient = RestClient.builder().baseUrl(wm.baseUrl() + "/feed").build();
            return new AtomiumClient(
                    new SpringFeedHttpClient(restClient, Map.of("variant", List.of("raw"))),
                    new JacksonFeedPageDecoder());
        }

        /** The stubs match on the query param: a fetch without it would 404 and fail the test. */
        @Test
        void theHeadFetchCarriesTheQueryParams() {
            wm.stubFor(get(urlPathEqualTo("/feed")).withQueryParam("variant", equalTo("raw"))
                    .willReturn(okJson(resource("2-v1.json"))));

            FetchResult result = clientWithQueryParams().fetchYoungest();

            assertThat(ids(result)).containsExactly("id-007", "id-008");
        }

        @Test
        void aPageFetchCombinesTheQueryParamsWithTheEtag() {
            wm.stubFor(get(urlPathEqualTo("/feed/2"))
                    .withQueryParam("variant", equalTo("raw"))
                    .withHeader("If-None-Match", equalTo("etag-x"))
                    .willReturn(aResponse().withStatus(304)));

            assertThat(clientWithQueryParams().fetch(onPage("/2", "id-008", "etag-x"))).isEmpty();
        }

        /** A multi-valued param (e.g. a server-side filter on multiple types) is sent once per value. */
        @Test
        void aMultiValuedQueryParamIsSentOncePerValue() {
            RestClient restClient = RestClient.builder().baseUrl(wm.baseUrl() + "/feed").build();
            var client = new AtomiumClient(
                    new SpringFeedHttpClient(restClient, Map.of("type", List.of("x", "y"))),
                    new JacksonFeedPageDecoder());
            wm.stubFor(get(urlPathEqualTo("/feed/1"))
                    .withQueryParam("type", equalTo("x"))
                    .withQueryParam("type", equalTo("y"))
                    .willReturn(okJson(resource("1.json"))));

            FetchResult result = client.fetch(new FeedPointer("/1")).orElseThrow();

            assertThat(ids(result)).containsExactly("id-004", "id-005", "id-006");
        }

        /** A page href may carry query params of its own; the configured params are added, not substituted. */
        @Test
        void theConfiguredParamsAreAddedToTheQueryParamsOfThePageHrefItself() {
            wm.stubFor(get(urlPathEqualTo("/feed/1"))
                    .withQueryParam("style", equalTo("compact"))
                    .withQueryParam("variant", equalTo("raw"))
                    .willReturn(okJson(resource("1.json"))));

            FetchResult result = clientWithQueryParams().fetch(new FeedPointer("/1?style=compact")).orElseThrow();

            assertThat(ids(result)).containsExactly("id-004", "id-005", "id-006");
        }
    }

    @Nested
    class HttpStatuses {

        @Test
        void status304YieldsEmptyResult() {
            wm.stubFor(get(urlPathEqualTo("/feed/2")).withHeader("If-None-Match", equalTo("etag-x"))
                    .willReturn(aResponse().withStatus(304)));

            assertThat(client().fetch(onPage("/2", "id-008", "etag-x"))).isEmpty();
        }

        @Test
        void status410ThrowsPageGoneException() {
            wm.stubFor(get(urlPathEqualTo("/feed/9")).willReturn(aResponse().withStatus(410)));

            assertThatThrownBy(() -> client().fetch(new FeedPointer("/9")))
                    .isInstanceOf(AtomiumPageGoneException.class);
        }

        @Test
        void status500ThrowsHttpException() {
            wm.stubFor(get(urlPathEqualTo("/feed/9")).willReturn(aResponse().withStatus(500)));

            assertThatThrownBy(() -> client().fetch(new FeedPointer("/9")))
                    .isInstanceOf(AtomiumHttpException.class)
                    .satisfies(e -> assertThat(((AtomiumHttpException) e).status()).isEqualTo(500));
        }
    }

    private static List<String> ids(FetchResult result) {
        return result.fetchEntries().stream().map(fetchEntry -> fetchEntry.entry().id()).toList();
    }

    /** Pointer that continues reading on the SAME page: the last processed event is on the fetch page (filter == id). */
    private static FeedPointer onPage(String page, @Nullable String lastEventId, @Nullable String etag) {
        EventCoordinate lastEvent = lastEventId == null ? null : new EventCoordinate(page, lastEventId);
        return new FeedPointer(lastEvent, new FetchCoordinate(page, lastEventId, etag));
    }

    private static String resource(String file) {
        String path = "/feedpages/" + file;
        try (InputStream in = SpringFeedHttpClientIT.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("resource not found: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
