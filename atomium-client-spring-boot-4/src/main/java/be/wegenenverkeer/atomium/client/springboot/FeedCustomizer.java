package be.wegenenverkeer.atomium.client.springboot;

/**
 * Adjust the configuration of one or more feeds before the framework builds the feed. Register an implementation
 * as a Spring bean; all {@code FeedCustomizer} beans are applied (in {@code @Order} order) to every feed,
 * after the defaults. The {@link FeedConfiguration} handed to you is therefore already fully populated.
 *
 * <p>This is the one customization SPI: a single concept for all per-feed variation points (instead of a
 * bean type per point), modeled on Spring's {@code RestClientCustomizer}. With multiple customizers on the
 * same feed, the last one executed wins — per variation point — (so the highest {@code @Order}).
 *
 * <pre>{@code
 * @Bean
 * FeedCustomizer orderFeed() {
 *     return FeedCustomizer.forFeed("a-feed-id", feed -> {
 *         feed.restClientBuilder().requestInterceptor(ownAuth());   // extend
 *         feed.setContentMapper(ownMapper());                       // replace
 *     });
 * }
 * }</pre>
 */
@FunctionalInterface
public interface FeedCustomizer {

    void customize(FeedConfiguration feed);

    /** Filter helper: apply {@code delegate} only to the feed with this {@code feedId}. */
    static FeedCustomizer forFeed(String feedId, FeedCustomizer delegate) {
        return feed -> {
            if (feed.feedId().equals(feedId)) {
                delegate.customize(feed);
            }
        };
    }
}
