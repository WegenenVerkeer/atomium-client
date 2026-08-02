package be.wegenenverkeer.atomium.client.core.demo.simpleprocessing;

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
 * The assembly of the {@code simple-processing} feed: identical to the minimal {@code SimpleDemoConfiguration},
 * plus the builder's two processing knobs ({@code maxProcessingSize} and {@code maxUncommittedPages}) — the only
 * assembly-side part of batching; what the batch does is a domain concern and lives in the
 * {@link SimpleProcessingDemoFeedHandler}.
 */
@Configuration
class SimpleProcessingDemoConfiguration {

    @Bean
    FeedRuntime simpleProcessingFeedRuntime(SimpleProcessingDemoFeedHandler handler, JsonMapper jsonMapper,
                                         DemoProperties properties) {
        AtomiumClient atomiumClient = DemoAtomiumClients.atomiumClient(handler.getFeedId(), properties.feedUrl());
        Feed<JsonNode> feed = Feed
                .builder(handler.getFeedId(), handler, atomiumClient,
                        JacksonFeedContentDecoder.of(handler, jsonMapper))
                .initialFeedPointer(atomiumClient::pointerToOldest)
                .queryInterval(properties.queryInterval())
                .activeOnStartup(properties.simpleProcessing().activeOnStartup())
                // batch tuning: the processing threshold, and the page safety net
                .maxProcessingSize(properties.simpleProcessing().maxProcessingSize())
                .maxUncommittedPages(properties.simpleProcessing().maxUncommittedPages())
                .build();
        return FeedRuntime.of(feed);
    }
}
