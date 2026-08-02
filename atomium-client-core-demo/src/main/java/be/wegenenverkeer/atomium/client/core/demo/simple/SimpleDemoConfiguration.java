package be.wegenenverkeer.atomium.client.core.demo.simple;

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
 * The <b>simplest possible</b> assembly of the handler API: the builder parameters (handler, client,
 * content decoder), a start position and the poll settings — the building blocks are the defaults (in-memory
 * pointers, no transactions, exponential backoff, own daemon thread). Fine for a demo; for production you pass a
 * real {@code FeedPointerRepository} and {@code FeedTransactions} — see the full-monty assembly for all options.
 */
@Configuration
class SimpleDemoConfiguration {

    @Bean
    FeedRuntime simpleFeedRuntime(SimpleDemoFeedHandler handler, JsonMapper jsonMapper, DemoProperties properties) {
        AtomiumClient atomiumClient = DemoAtomiumClients.atomiumClient(handler.getFeedId(), properties.feedUrl());
        Feed<JsonNode> feed = Feed
                .builder(handler.getFeedId(), handler, atomiumClient,
                        JacksonFeedContentDecoder.of(handler, jsonMapper))
                .initialFeedPointer(atomiumClient::pointerToOldest)
                .queryInterval(properties.queryInterval())
                .activeOnStartup(properties.simple().activeOnStartup())
                .build();
        return FeedRuntime.of(feed);
    }
}
