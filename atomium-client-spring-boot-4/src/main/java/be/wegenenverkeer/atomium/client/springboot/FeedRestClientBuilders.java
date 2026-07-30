package be.wegenenverkeer.atomium.client.springboot;

import org.springframework.web.client.RestClient;

/**
 * The <em>only</em> required seam an application must implement: supply per feed a {@link RestClient.Builder}
 * that knows how to reach the source feed (base url, TLS, auth, logging, retries). All the rest of the
 * {@link FeedConfiguration} (content mapper, executor, backoff) is populated with defaults by the framework — still
 * overridable per feed via a {@link FeedCustomizer}.
 *
 * <p>This is deliberately a <em>narrow</em> seam: an application without special HTTP needs writes one line
 * ({@code (feedId, properties) -> RestClient.builder().baseUrl(properties.url())}). An application with a shared
 * infrastructure (TLS, auth, logging, retries, a gateway) bundles it here: this is the only place with
 * environment-specific knowledge.
 *
 * <p>This implementation often does not need to be rewritten per application: within the same team or
 * organization the way a source feed is reached (the same gateway, auth and client conventions) is usually
 * identical. It is then a good candidate for that team to ship once in a shared library, so that every
 * application only pulls it in as a dependency.
 *
 * <p>The builder (not the built client) is placed in the {@link FeedConfiguration}, so that a {@link FeedCustomizer}
 * can still add an interceptor to it before it is built.
 */
@FunctionalInterface
public interface FeedRestClientBuilders {

    RestClient.Builder restClientBuilderFor(String feedId, AtomiumFeedProperties properties);
}
