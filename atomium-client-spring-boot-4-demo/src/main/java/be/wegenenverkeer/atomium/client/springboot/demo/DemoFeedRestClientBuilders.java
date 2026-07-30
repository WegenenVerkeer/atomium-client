package be.wegenenverkeer.atomium.client.springboot.demo;

import be.wegenenverkeer.atomium.client.springboot.AtomiumFeedProperties;
import be.wegenenverkeer.atomium.client.springboot.FeedRestClientBuilders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * <b>The mandatory seam</b> of {@code atomium-client-spring-boot-4}: the only thing the generic lib asks of an
 * application. Supply per feed a {@link RestClient.Builder} that knows how to reach the source feed. Without a
 * {@link FeedRestClientBuilders} bean the autoconfig cannot build the {@code FeedFactory} and the app does
 * <em>not</em> start (that is the deliberate, mandatory seam — the context load test proves exactly that this bean
 * is present).
 *
 * <p>For this demo the generic feed {@code url} from {@code atomium.feeds.<feedId>.url} suffices. We immediately
 * add one thing a real application typically sets up here as well: <b>logging</b>, via a
 * {@code requestInterceptor} (TLS/auth/retries would go in the same place). You need not supply a content mapper,
 * executor or backoff: those come as framework defaults.
 */
@Component
public class DemoFeedRestClientBuilders implements FeedRestClientBuilders {

    private static final Logger LOG = LoggerFactory.getLogger(DemoFeedRestClientBuilders.class);

    @Override
    public RestClient.Builder restClientBuilderFor(String feedId, AtomiumFeedProperties properties) {
        return RestClient.builder()
                .baseUrl(properties.url())
                .requestInterceptor(loggingInterceptor(feedId));
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
