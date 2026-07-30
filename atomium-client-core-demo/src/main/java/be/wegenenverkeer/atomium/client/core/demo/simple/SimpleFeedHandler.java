package be.wegenenverkeer.atomium.client.core.demo.simple;

import be.wegenenverkeer.atomium.client.core.demo.DemoFeedEndpoint;
import be.wegenenverkeer.atomium.client.handler.EntryFeedHandler;
import be.wegenenverkeer.atomium.client.protocol.AtomiumEntry;
import be.wegenenverkeer.atomium.client.protocol.FeedPageMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * The active demo feed ({@code simple}): consumes the in-memory {@link DemoFeedEndpoint} and logs every event.
 */
@Component
public class SimpleFeedHandler implements EntryFeedHandler<JsonNode> {

    private static final Logger LOG = LoggerFactory.getLogger(SimpleFeedHandler.class);

    @Override
    public String getFeedId() {
        return "simple";
    }

    @Override
    public void onEntry(FeedPageMetadata pageMetadata, AtomiumEntry entry, JsonNode content) {
        LOG.info("new event id={} updated={} content={}", entry.id(), entry.updated(), content);
    }
}
