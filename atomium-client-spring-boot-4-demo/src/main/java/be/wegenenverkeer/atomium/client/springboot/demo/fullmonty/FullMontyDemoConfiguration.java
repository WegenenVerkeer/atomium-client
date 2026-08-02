package be.wegenenverkeer.atomium.client.springboot.demo.fullmonty;

import be.wegenenverkeer.atomium.client.springboot.FeedConfiguration;
import be.wegenenverkeer.atomium.client.springboot.FeedCustomizer;
import be.wegenenverkeer.atomium.client.handler.FeedEventListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Purely for demonstration: one {@link FeedCustomizer} that — for the {@code full-monty} feed only — replaces
 * <em>every</em> per-feed variation point of {@link FeedConfiguration} with a replacement of its own. That way one class shows which
 * options the customizer SPI offers (besides the YAML properties on the feed and the handler callbacks in
 * {@link FullMontyDemoFeedHandler}). The replacements here are deliberately no-ops: show the customizer SPI, not
 * actually use it.
 */
@Configuration
public class FullMontyDemoConfiguration {

    @Bean
    FeedCustomizer fullMontyVariationPoints() {
        return FeedCustomizer.forFeed("full-monty", feed -> {
            // the HTTP client builder: augment (feed.restClientBuilder().requestInterceptor(...)) or replace entirely
            feed.setRestClientBuilder(feed.restClientBuilder());
            // the JsonMapper the entry content is deserialized with
            feed.setContentMapper(feed.getContentMapper());
            // the Executor (thread) the runs of this feed execute on
            feed.setExecutor(feed.getExecutor());
            // the backoff policy on consecutive failed runs
            feed.setBackoffPolicy(feed.getBackoffPolicy());
            // event listeners are additive (no setter): this is how you add one per feed
            feed.addListener(new FeedEventListener() {
            });
        });
    }
}
