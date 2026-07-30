package be.wegenenverkeer.atomium.client.core.demo.simplebatched;

import be.wegenenverkeer.atomium.client.handler.BatchEntry;
import be.wegenenverkeer.atomium.client.handler.BatchedFeedHandler;
import be.wegenenverkeer.atomium.client.handler.DefaultFeedHandlerBatch;
import be.wegenenverkeer.atomium.client.handler.FeedHandlerBatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * The simplest possible batch demo ({@code simple-batched}): a {@link BatchedFeedHandler} on a raw
 * {@code JsonNode}, with the bundled {@link DefaultFeedHandlerBatch} — for a feed that delivers events in bursts
 * faster than you want to commit them one by one. The batch and the feed pointer are committed together; the
 * batch size comes from the assembly ({@code demo.simple-batched.preferred-batch-size}).
 *
 * <p>It is deliberately <em>inactive</em>: activate it during the demo
 * ({@code PUT /rest/demo/feeds/simple-batched/activate}) — because the {@code simple} feed has meanwhile made the
 * same demo feed grow, a backlog is already waiting, which you then immediately see being processed in batches in
 * the logs.
 */
@Component
public class SimpleBatchedFeedHandler implements BatchedFeedHandler<JsonNode> {

    private static final Logger LOG = LoggerFactory.getLogger(SimpleBatchedFeedHandler.class);

    @Override
    public String getFeedId() {
        return "simple-batched";
    }

    /**
     * The dedup key is a domain concern and therefore belongs here in code: in a real app the entity id, so that for a burst
     * on the same entity only the last state gets processed. The demo feed delivers nothing but unique events, so
     * effectively nothing dedups here.
     */
    @Override
    public FeedHandlerBatch<JsonNode> startBatch(int preferredBatchSize) {
        return new DefaultFeedHandlerBatch<>(preferredBatchSize, content -> content.get("aField"));
    }

    @Override
    public void onBatch(FeedHandlerBatch<JsonNode> batch) {
        LOG.info("batch of {} event(s):", batch.getBuffer().size());
        for (BatchEntry<JsonNode> batchEntry : batch.getBuffer()) {
            LOG.info("  - id={} updated={} content={}",
                    batchEntry.entry().id(), batchEntry.entry().updated(), batchEntry.content());
        }
    }
}
