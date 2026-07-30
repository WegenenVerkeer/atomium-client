package be.wegenenverkeer.atomium.client.port;

import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * HTTP port for the HTTP GETs that fetch the feed from the server's REST endpoint.
 *
 * <p>The application supplies the implementation
 * (e.g. the implementation of the atomium-client-restclient library based on the Spring RestClient
 * if the application uses Spring)</p>
 *
 * <p>The adapter is responsible for the absolute URL (base url), logging, retries, timeouts, authentication, etc.</p>
 */
public interface FeedHttpClient {

    /**
     * The result of a GET.
     *
     * @param status  the HTTP status code (e.g. 200, 304, 410)
     * @param headers the response headers
     * @param body    the response body, or {@code null} (e.g. on a 304)
     */
    record HttpResponse(int status, Map<String, String> headers, @Nullable String body) {

        public HttpResponse {
            // HTTP header names are case-insensitive; the supplied map may use arbitrary casing
            // (depending on the HTTP client). We normalize to a case-insensitive map so that
            // lookups such as etag() ("etag") are reliable regardless of the source.
            Map<String, String> caseInsensitive = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            caseInsensitive.putAll(headers);
            headers = Collections.unmodifiableMap(caseInsensitive);
        }

        /** The {@code ETag} response header, or {@code null} if the server does not set one. */
        public @Nullable String etag() {
            return headers.get("etag");
        }
    }

    /**
     * Perform an HTTP GET.
     *
     * @param relativeLink the page href relative to the feed base url (e.g. {@code "/182"}).
     *                     An <strong>empty string</strong> means the head of the feed (the base url
     *                     without page href).
     * @param etag         if non-{@code null}: sent as {@code If-None-Match} header so that the
     *                     server can answer with a {@code 304 Not Modified}. Not every server
     *                     supports this.
     * @return the HTTP response; transient failures are preferably handled inside this adapter itself
     *         (retries), so that the client sees a definitive response or an exception
     */
    HttpResponse get(String relativeLink, @Nullable String etag);
}
