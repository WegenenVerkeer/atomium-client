package be.wegenenverkeer.atomium.client.springboot;

import be.wegenenverkeer.atomium.client.handler.FeedHandler;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.Map;

/**
 * All atomium config, bound under the prefix {@code atomium}. One recognizable block instead of scattered
 * top-level properties, with configuration metadata (IDE completion + typo detection via the
 * {@code spring-boot-configuration-processor}).
 *
 * <pre>{@code
 * atomium:
 *   feeds:
 *     a-feed-id:
 *       url: https://…
 *       query-interval: 30s
 *       active-on-startup: true
 *       initial-feed-pointer.type: now
 *   admin:
 *     enabled: true
 *     pretty-print: true
 * }</pre>
 *
 * @param feeds  the config per feed, with the {@link FeedHandler#getFeedId() feedId} as map key
 * @param admin  the config of the admin/diagnostics endpoint
 * @param health the config of the health indicator
 */
@ConfigurationProperties(prefix = "atomium")
public record AtomiumProperties(
        @DefaultValue Map<String, AtomiumFeedProperties> feeds,
        @DefaultValue Admin admin,
        @DefaultValue Health health
) {

    /**
     * @param enabled     whether the admin endpoint is registered (default {@code false}; also used directly as
     *                    {@code @ConditionalOnProperty}, so that the beans only come into existence at {@code true})
     * @param prettyPrint whether the admin endpoint returns its JSON indented (default {@code true})
     */
    public record Admin(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("true") boolean prettyPrint
    ) {
    }

    /**
     * @param enabled             whether the per-feed health indicator is registered (default {@code true};
     *                            additionally requires the Spring Boot health API on the classpath)
     * @param failureThreshold the number of <em>consecutive</em> failed runs after which a feed reports {@code DOWN}
     *                            (at least 1); below the threshold a failure is transient — the backoff resolves it
     *                            by itself, and a health that flaps on it teaches people to ignore it
     */
    public record Health(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("3") int failureThreshold
    ) {

        public Health {
            if (failureThreshold < 1) {
                throw new IllegalArgumentException(
                        "health.failure-threshold must be at least 1, was " + failureThreshold);
            }
        }
    }
}
