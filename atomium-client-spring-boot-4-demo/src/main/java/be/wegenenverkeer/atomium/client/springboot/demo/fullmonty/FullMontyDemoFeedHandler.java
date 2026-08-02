package be.wegenenverkeer.atomium.client.springboot.demo.fullmonty;

import be.wegenenverkeer.atomium.client.handler.EntryFeedHandler;
import be.wegenenverkeer.atomium.client.protocol.AtomiumEntry;
import be.wegenenverkeer.atomium.client.protocol.FeedPageMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * The fully equipped demo feed ({@code full-monty}): an {@link EntryFeedHandler} that,
 * on top of the simple {@code SimpleDemoFeedHandler}, shows everything else that is possible:
 *
 * <ul>
 *   <li>a <b>custom content type</b> ({@link MontyContent}) instead of a raw {@code JsonNode};</li>
 *   <li>filtering of entries with <b>accepts</b>;</li>
 *   <li>processing of content pushed via the admin endpoint instead of received via the feed: <b>pushEntry</b>;</li>
 *   <li>all the per-feed properties, each with a comment ({@code application.yml});</li>
 *   <li>the per-feed <b>customizer</b> variation points, separately in {@link FullMontyDemoConfiguration}.</li>
 * </ul>
 *
 * <p>In {@code application.yml} it is deliberately <em>inactive</em> ({@code active-on-startup: false}), so you
 * activate it yourself during the demo (via the admin endpoint or by setting the property to {@code true}).
 * Because the {@code simple} feed polls the same in-memory demo feed and makes it grow with every poll, a
 * <b>backlog</b> is already waiting when you activate, and it gets processed immediately.
 *
 * <p>For batch processing (a {@code SimpleProcessingFeedHandler}) there is the separate {@code simple-processing} demo.
 */
@Component
public class FullMontyDemoFeedHandler implements EntryFeedHandler<MontyContent> {

    private static final Logger LOG = LoggerFactory.getLogger(FullMontyDemoFeedHandler.class);

    @Override
    public String getFeedId() {
        return "full-monty";
    }

    @Override
    public void onEntry(FeedPageMetadata pageMetadata, AtomiumEntry entry, MontyContent content) {
        processEntry(entry.id(), entry.updated(), content);
    }

    /**
     * Is this entry relevant to this handler? {@code false} → the framework ignores it completely (no
     * entry callback) and simply advances the feed pointer past it.
     */
    @Override
    public boolean accepts(FeedPageMetadata pageMetadata, AtomiumEntry entry, MontyContent content) {
        return true;
    }

    /**
     * Processes a content item <em>as if</em> it had been an entry on the feed (e.g. to correct a failure of the
     * source application via the admin endpoint without it having to ship a new release).
     */
    @Override
    public void pushEntry(MontyContent content) {
        processEntry("PUSH", OffsetDateTime.now(), content);
    }

    private void processEntry(String id, OffsetDateTime updated, MontyContent content) {
        LOG.info("new event id={} updated={} field={}", id, updated, content.aField());
    }
}
