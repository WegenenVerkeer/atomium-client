package be.wegenenverkeer.atomium.client.core.demo.simplebatched;

import be.wegenenverkeer.atomium.client.core.demo.DemoAtomiumClients;
import be.wegenenverkeer.atomium.client.core.demo.DemoProperties;
import be.wegenenverkeer.atomium.client.fetch.AtomiumClient;
import be.wegenenverkeer.atomium.client.handler.Feed;
import be.wegenenverkeer.atomium.client.handler.FeedRuntime;
import be.wegenenverkeer.atomium.client.jackson.JacksonFeedContentDecoder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The assembly of the {@code simple-batched} feed: identical to the minimal {@code SimpleDemoConfiguration},
 * plus the builder's two batch knobs ({@code preferredBatchSize} and {@code maxUnflushedPages}) — the only
 * assembly-side part of batching; the rest (key, batch implementation) is a domain concern and lives in the
 * {@link SimpleBatchedFeedHandler}.
 */
@Configuration
class SimpleBatchedDemoConfiguration {

    @Bean
    FeedRuntime simpleBatchedFeedRuntime(SimpleBatchedFeedHandler handler, JsonMapper jsonMapper,
                                         DemoProperties properties) {
        AtomiumClient atomiumClient = DemoAtomiumClients.atomiumClient(handler.getFeedId(), properties.feedUrl());
        Feed<JsonNode> feed = Feed
                .builder(handler.getFeedId(), handler, atomiumClient,
                        JacksonFeedContentDecoder.of(handler, jsonMapper))
                .initialFeedPointer(atomiumClient::pointerToOldest)
                .queryInterval(properties.queryInterval())
                .activeOnStartup(properties.simpleBatched().activeOnStartup())
                // batch tuning: the threshold the batch is given, and the page safety net
                .preferredBatchSize(properties.simpleBatched().preferredBatchSize())
                .maxUnflushedPages(properties.simpleBatched().maxUnflushedPages())
                .build();
        return FeedRuntime.of(feed);
    }
}
