package be.wegenenverkeer.atomium.client.fetch;

import be.wegenenverkeer.atomium.client.port.FeedHttpClient;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * An in-memory {@link FeedHttpClient} for the tests. Pages are registered under their relative link; the
 * head ("") refers to a configured link.
 *
 * <p>ETag behavior is configurable: a page with an etag simulates a server <em>with</em>
 * etag support (a request with a matching {@code If-None-Match} gets a 304); a
 * page without an etag simulates a server <em>without</em> etag support (always 200).
 */
public final class FakeFeedHttpClient implements FeedHttpClient {

    private record Stored(int status, @Nullable String etag, @Nullable String body) {
    }

    private final Map<String, Stored> pages = new HashMap<>();
    private final List<Call> calls = new ArrayList<>();
    private String headLink = "";

    record Call(String relativeLink, @Nullable String etag) {
    }

    /** Place a normal (200) page, with an optional etag. */
    public FakeFeedHttpClient page(String link, String body, @Nullable String etag) {
        pages.put(link, new Stored(200, etag, body));
        return this;
    }

    /** Place a normal (200) page without an etag (server without etag support). */
    public FakeFeedHttpClient page(String link, String body) {
        return page(link, body, null);
    }

    /** Make the head ("") refer to this link. */
    public FakeFeedHttpClient head(String link) {
        this.headLink = link;
        return this;
    }

    /** Make a link return an arbitrary status. */
    FakeFeedHttpClient status(String link, int status) {
        pages.put(link, new Stored(status, null, null));
        return this;
    }

    /** Make a link answer with status 200 but without a body (to test the failure handling). */
    FakeFeedHttpClient pageWithoutBody(String link) {
        pages.put(link, new Stored(200, null, null));
        return this;
    }

    @Override
    public HttpResponse get(String relativeLink, @Nullable String etag) {
        calls.add(new Call(relativeLink, etag));
        String key = relativeLink.isEmpty() ? headLink : relativeLink;
        Stored stored = pages.get(key);
        if (stored == null) {
            return new HttpResponse(404, Map.of(), null);
        }
        if (stored.status() == 200 && etag != null && etag.equals(stored.etag())) {
            return new HttpResponse(304, headers(stored.etag()), null);
        }
        return new HttpResponse(stored.status(), headers(stored.etag()), stored.body());
    }

    private static Map<String, String> headers(@Nullable String etag) {
        return etag == null ? Map.of() : Map.of("ETag", etag);
    }

    /** All GET calls, in order. Handy to verify the etag/link behavior. */
    List<Call> calls() {
        return calls;
    }

    Call lastCall() {
        return calls.getLast();
    }
}
