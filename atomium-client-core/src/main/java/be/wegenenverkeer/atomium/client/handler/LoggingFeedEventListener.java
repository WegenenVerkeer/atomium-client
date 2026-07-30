package be.wegenenverkeer.atomium.client.handler;

import be.wegenenverkeer.atomium.client.fetch.FeedPointer;
import be.wegenenverkeer.atomium.client.protocol.FeedPageMetadata;
import be.wegenenverkeer.atomium.client.protocol.FeedPageRel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * The bundled standard {@link FeedEventListener}: logs the feed events at DEBUG (per entry and fine-grained
 * processing details), INFO (per page and run) and WARN (on a failed run). Doubles as proof that the SPI
 * works and as a usable default; replaceable, and an app can put its own listeners (metrics/health) alongside it.
 */
// deliberately non-final: an app overriding a few log callbacks is an intended extension point
public class LoggingFeedEventListener implements FeedEventListener {

    private static final Logger LOG = LoggerFactory.getLogger(LoggingFeedEventListener.class);

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    @Override
    public void runStarted(String feedId, FeedPointer startPosition) {
        LOG.info("feed '{}': run started from page '{}'", feedId, startPosition.nextFetch().pageLink());
    }

    @Override
    public void pageFetched(String feedId, FeedPageMetadata pageMetadata, int entryCount) {
        LOG.info("feed '{}': page '{}' fetched ({} entries)",
                feedId, pageMetadata.href(FeedPageRel.SELF), entryCount);
    }

    @Override
    public void feedNotModified(String feedId) {
        LOG.info("feed '{}': nothing changed since the previous poll", feedId);
    }

    @Override
    public void entriesProcessed(String feedId, List<? extends BatchEntry<?>> entries) {
        if (!LOG.isDebugEnabled()) {
            return;
        }
        for (BatchEntry<?> batchEntry : entries) {
            LOG.debug("feed '{}': processed entry '{}' (updated {})",
                    feedId, batchEntry.entry().id(), TIMESTAMP.format(batchEntry.entry().updated()));
        }
    }

    @Override
    public void feedPointerAdvanced(String feedId, FeedPointer feedPointer, FeedRunResult sincePreviousCommit) {
        LOG.debug("feed '{}': feed pointer committed at page '{}'", feedId, feedPointer.nextFetch().pageLink());
    }

    @Override
    public void pageProcessed(String feedId, FeedPageMetadata pageMetadata) {
        LOG.debug("feed '{}': page '{}' processed", feedId, pageMetadata.href(FeedPageRel.SELF));
    }

    @Override
    public void endOfFeedReached(String feedId) {
        LOG.info("feed '{}': head reached (no younger events)", feedId);
    }

    @Override
    public void runInterrupted(String feedId, FeedRunResult result) {
        LOG.info("feed '{}': run interrupted after {} processed entries; the next run resumes the rest",
                feedId, result.processed());
    }

    @Override
    public void runCompleted(String feedId, FeedRunResult result) {
        LOG.info("feed '{}': run completed; {} read, {} accepted, {} processed",
                feedId, result.read(), result.accepted(), result.processed());
    }

    @Override
    public void runFailed(FeedRunFailure failure) {
        // the ERROR log (incl. stack trace + backoff) already lives in FeedRunner; here a concise WARN event trail
        LOG.warn("feed '{}': run failed (attempt {}, next attempt after {})",
                failure.feedId(), failure.consecutiveFailures(), failure.nextAttemptAfter());
    }
}
