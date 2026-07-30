package be.wegenenverkeer.atomium.client.core.demo;

import be.wegenenverkeer.atomium.client.fetch.AtomiumClient;
import be.wegenenverkeer.atomium.client.jackson.JacksonFeedPageDecoder;
import be.wegenenverkeer.atomium.client.spring.SpringFeedHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClient;

/**
 * Builds the {@link AtomiumClient} per feed: the {@code FeedHttpClient} port implemented by
 * {@code atomium-client-restclient} (Spring's {@link RestClient}, here with base url + a logging interceptor —
 * TLS/auth/retries would go in the same place) and the {@code FeedPageDecoder} port by
 * {@code atomium-client-jackson-3}. This is the HTTP part every stack provides itself; the rest of the demo is
 * independent of it.
 */
public final class DemoAtomiumClients {

    private static final Logger LOG = LoggerFactory.getLogger(DemoAtomiumClients.class);

    private DemoAtomiumClients() {
    }

    /** An {@link AtomiumClient} for this feed (one client per feed: the base url lives in the HTTP client). */
    public static AtomiumClient atomiumClient(String feedId, String feedUrl) {
        RestClient restClient = RestClient.builder()
                .baseUrl(feedUrl)
                .requestInterceptor(loggingInterceptor(feedId))
                .build();
        return new AtomiumClient(new SpringFeedHttpClient(restClient), new JacksonFeedPageDecoder());
    }

    /** Logs every HTTP call of this feed at DEBUG: the request, and the response with duration and status. */
    private static ClientHttpRequestInterceptor loggingInterceptor(String feedId) {
        return (request, body, execution) -> {
            if (!LOG.isDebugEnabled()) {
                return execution.execute(request, body);
            }
            LOG.debug("-REQUEST-> {} {} {}", feedId, request.getMethod(), request.getURI());
            long startNanos = System.nanoTime();
            ClientHttpResponse response = execution.execute(request, body);
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
            LOG.debug("<-RESPONSE- {} {} {}\n    Time: {}ms\n    Status: {}",
                    feedId, request.getMethod(), request.getURI(), durationMs, response.getStatusCode().value());
            return response;
        };
    }
}
