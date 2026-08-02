package be.wegenenverkeer.atomium.client.core.demo.simpleprocessing;

import be.wegenenverkeer.atomium.client.handler.ProcessResult;
import be.wegenenverkeer.atomium.client.handler.ProcessingEntry;
import be.wegenenverkeer.atomium.client.handler.SimpleProcessingFeedHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * The simplest possible two-phase processing demo ({@code simple-processing}): a {@link SimpleProcessingFeedHandler} on a
 * raw {@code JsonNode}, showing the two phases — {@code process} prepares the batch outside the transaction (in
 * a real app: collect ids and look them up remotely), {@code persist} writes the prepared result inside the
 * transaction that also advances the feed pointer.
 *
 * <p><b>Expect varying batch sizes in the logs.</b> {@code demo.simple-processing.max-processing-size} is only
 * the <em>maximum</em>, and it counts <em>accepted</em> entries. A batch is wrapped up by whichever comes
 * first: the maximum, the safety net ({@code maxUncommittedPages}), the end of the feed, an interruption, or
 * a read failure — so the tail of a backlog and a quiet feed produce partial batches by design.
 *
 * <p>It is deliberately <em>inactive</em>: activate it during the demo
 * ({@code PUT /rest/demo/feeds/simple-processing/activate}) — because the {@code simple} feed has meanwhile made the
 * same demo feed grow, a backlog is already waiting, which you then immediately see being processed in batches in
 * the logs.
 */
@Component
public class SimpleProcessingDemoFeedHandler implements SimpleProcessingFeedHandler<JsonNode, List<String>> {

    private static final Logger LOG = LoggerFactory.getLogger(SimpleProcessingDemoFeedHandler.class);

    @Override
    public String getFeedId() {
        return "simple-processing";
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
