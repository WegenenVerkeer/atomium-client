package be.wegenenverkeer.atomium.client.springboot.demo.simplebatched;

import be.wegenenverkeer.atomium.client.handler.ProcessResult;
import be.wegenenverkeer.atomium.client.handler.ProcessingEntry;
import be.wegenenverkeer.atomium.client.handler.SimpleBatchedProcessingFeedHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * The simplest possible batch demo ({@code simple-batched}): a {@link SimpleBatchedProcessingFeedHandler} on a
 * raw {@code JsonNode}, showing the two phases — {@code process} prepares the batch outside the transaction (in
 * a real app: collect ids and look them up remotely), {@code persist} writes the prepared result inside the
 * transaction that also advances the feed pointer. The batch size comes from the config
 * ({@code atomium.feeds.simple-batched.processing.preferred-size}).
 *
 * <p>In {@code application.yml} it is deliberately <em>inactive</em>: activate it during the demo (admin
 * endpoint) — because the {@code simple} feed has meanwhile made the same demo feed grow, a backlog is already
 * waiting, which you then immediately see being processed in batches in the logs.
 */
@Component
public class SimpleBatchedFeedHandler implements SimpleBatchedProcessingFeedHandler<JsonNode, List<String>> {

    private static final Logger LOG = LoggerFactory.getLogger(SimpleBatchedFeedHandler.class);

    @Override
    public String getFeedId() {
        return "simple-batched";
    }

    /** Phase 1, outside the transaction: prepare the batch (in a real app: collect, dedupe, look up remotely). */
    @Override
    public ProcessResult<List<String>> process(List<ProcessingEntry<JsonNode>> entries) {
        LOG.info("processing a batch of {} event(s)", entries.size());
        List<String> prepared = entries.stream()
                .map(entry -> "id=%s updated=%s content=%s"
                        .formatted(entry.entry().id(), entry.entry().updated(), entry.content()))
                .toList();
        return ProcessResult.of(prepared);
    }

    /** Phase 2, inside the transaction (together with the feed pointer): persist the prepared effect. */
    @Override
    public void persist(List<String> prepared) {
        prepared.forEach(line -> LOG.info("  - {}", line));
    }
}
