package be.wegenenverkeer.atomium.client.core.demo.fullmonty;

import be.wegenenverkeer.atomium.client.handler.EntryFeedHandler;
import be.wegenenverkeer.atomium.client.protocol.AtomiumEntry;
import be.wegenenverkeer.atomium.client.protocol.FeedPageMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * The fully equipped demo feed ({@code full-monty}): an {@link EntryFeedHandler} — the typical case — that,
 * on top of the simple {@code SimpleFeedHandler}, shows the remaining handler callbacks:
 *
 * <ul>
 *   <li>a <b>custom content type</b> ({@link MontyContent}) instead of a raw {@code JsonNode};</li>
 *   <li>filtering of entries with <b>accepts</b>;</li>
 *   <li>processing of content that was pushed instead of received via the feed: <b>pushEntry</b>
 *       ({@code POST /rest/demo/feeds/full-monty/push}).</li>
 * </ul>
 *
 * <p>It is deliberately <em>inactive</em> ({@code demo.full-monty.active-on-startup: false}), so you activate it
 * yourself during the demo ({@code PUT /rest/demo/feeds/full-monty/activate}). Because the {@code simple} feed
 * polls the same in-memory demo feed and makes it grow with every poll, a <b>backlog</b> is already waiting when
 * you activate, and it gets processed immediately.
 *
 * <p>The assembly with all the building blocks lives in {@link FullMontyDemoConfiguration}; for batch processing
 * (a {@code BatchedFeedHandler}) there is the separate {@code simple-batched} demo.
 */
@Component
public class FullMontyFeedHandler implements EntryFeedHandler<MontyContent> {

    private static final Logger LOG = LoggerFactory.getLogger(FullMontyFeedHandler.class);

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
     * source application without it having to ship a new release).
     */
    @Override
    public void pushEntry(MontyContent content) {
        processEntry("PUSH", OffsetDateTime.now(), content);
    }

    private void processEntry(String id, OffsetDateTime updated, MontyContent content) {
        LOG.info("new event id={} updated={} field={}", id, updated, content.aField());
    }
}
