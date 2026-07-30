package be.wegenenverkeer.atomium.client.spring;

import be.wegenenverkeer.atomium.client.port.FeedHttpClient;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

/**
 * {@link FeedHttpClient} based on the Spring {@link RestClient}.
 *
 * <p>The {@code RestClient} is supplied by the application and comes preconfigured with the feed base url
 * (and, if desired, logging, retries, timeouts, authentication, …). The relative link is <em>appended to
 * the base-url path</em> (not resolved as a URI, which would drop the base path); an empty string yields
 * the head of the feed.
 */
public final class SpringFeedHttpClient implements FeedHttpClient {

    private final RestClient restClient;

    public SpringFeedHttpClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public HttpResponse get(String relativeLink, @Nullable String etag) {
        RestClient.RequestHeadersSpec<?> request = restClient.get()
                .uri(uriBuilder -> uriBuilder.path(relativeLink).build());
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
