package be.wegenenverkeer.atomium.client.spring;

import be.wegenenverkeer.atomium.client.port.FeedHttpClient;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link FeedHttpClient} based on the Spring {@link RestClient}.
 *
 * <p>The {@code RestClient} is supplied by the application and comes preconfigured with the feed base url
 * (and, if desired, logging, retries, timeouts, authentication, …). The relative link is <em>appended to
 * the base-url path</em> (not resolved as a URI, which would drop the base path); an empty string yields
 * the head of the feed. A query string in the relative link is preserved as-is.
 *
 * <p>Optionally takes query parameters that are sent with <em>every</em> GET (the head as well as every
 * page), for feed servers whose behavior is steered per request (e.g. a server-side filter, possibly
 * multi-valued). They are <em>added to</em> any query parameters the page href itself carries.
 */
public final class SpringFeedHttpClient implements FeedHttpClient {

    private final RestClient restClient;
    private final Map<String, List<String>> queryParams;

    public SpringFeedHttpClient(RestClient restClient) {
        this(restClient, Map.of());
    }

    public SpringFeedHttpClient(RestClient restClient, Map<String, List<String>> queryParams) {
        this.restClient = restClient;
        // deep, ordered copy: deterministic request URIs (stable logging, stubbable in tests)
        Map<String, List<String>> copy = new LinkedHashMap<>();
        queryParams.forEach((name, values) -> copy.put(name, List.copyOf(values)));
        this.queryParams = Collections.unmodifiableMap(copy);
    }

    @Override
    public HttpResponse get(String relativeLink, @Nullable String etag) {
        // split off the href's own query string: path() would percent-encode a '?', and the configured
        // params must be added to the href's params, not replace them
        int questionMark = relativeLink.indexOf('?');
        String path = questionMark < 0 ? relativeLink : relativeLink.substring(0, questionMark);
        String hrefQuery = questionMark < 0 ? null : relativeLink.substring(questionMark + 1);

        RestClient.RequestHeadersSpec<?> request = restClient.get()
                .uri(uriBuilder -> {
                    uriBuilder.path(path);
                    if (hrefQuery != null) {
                        uriBuilder.query(hrefQuery);
                    }
                    queryParams.forEach(uriBuilder::queryParam);
                    return uriBuilder.build();
                });
        if (etag != null) {
            request.header(HttpHeaders.IF_NONE_MATCH, etag);
        }
        // exchange() deliberately applies no default status handlers: 304/410/5xx come back as a response
        // (not as an exception), so AtomiumClient can decide for itself based on the status.
        return request.exchange((req, response) -> new HttpResponse(
                response.getStatusCode().value(),
                response.getHeaders().toSingleValueMap(),
                response.bodyTo(String.class)
        ));
    }
}
