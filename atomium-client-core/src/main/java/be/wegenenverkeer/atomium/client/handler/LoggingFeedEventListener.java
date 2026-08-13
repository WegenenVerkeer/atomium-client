package be.wegenenverkeer.atomium.client.handler;

import be.wegenenverkeer.atomium.client.fetch.FeedPointer;
import be.wegenenverkeer.atomium.client.protocol.FeedPageMetadata;
import be.wegenenverkeer.atomium.client.protocol.FeedPageRel;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

/**
 * The bundled standard {@link FeedEventListener}: logs the feed events at DEBUG (fine-grained processing
 * details, and everything a quiet poll produces), INFO (the run start, and pages/runs that actually deliver
 * events), WARN (on a failed run) and ERROR (on a failed {@code afterCommit} hook — the run continues, so
 * this line is all the failure gets). A quiet poll of an idle feed thus costs one INFO line (the run start;
 * the {@code FeedRunner} adds its completion line with the next poll moment) instead of a five-line block
 * per poll. Doubles as proof that the SPI works and as a usable default; replaceable, and an app can put its
 * own listeners (metrics/health) alongside it.
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
        // an empty page (the head of an idle feed, every poll) is noise at INFO; a page with events is news
        if (entryCount > 0) {
            LOG.info("feed '{}': page '{}' fetched ({} entries)",
                    feedId, pageMetadata.href(FeedPageRel.SELF), entryCount);
        } else {
            LOG.debug("feed '{}': page '{}' fetched (0 entries)",
                    feedId, pageMetadata.href(FeedPageRel.SELF));
        }
    }

    @Override
    public void feedNotModified(String feedId) {
        // how the run ended is a detail: runCompleted follows immediately anyway
        LOG.debug("feed '{}': nothing changed since the previous poll", feedId);
    }

    @Override
    public void feedPointerAdvanced(String feedId, FeedPointer feedPointer, FeedRunResult sincePreviousCommit,
                                    @Nullable OffsetDateTime latestEventUpdated) {
        LOG.debug("feed '{}': feed pointer committed at page '{}' ({} read, {} accepted, {} processed{})",
                feedId, feedPointer.nextFetch().pageLink(),
                sincePreviousCommit.read(), sincePreviousCommit.accepted(), sincePreviousCommit.processed(),
                latestEventUpdated == null ? "" : "; latest event " + TIMESTAMP.format(latestEventUpdated));
    }

    @Override
    public void afterCommitCompleted(String feedId, @Nullable Throwable failure) {
        if (failure != null) {
            // the run does not fail (the batch is committed), so this is the only log line the failure gets
            LOG.error("feed '{}': the afterCommit hook failed; the run continues", feedId, failure);
        } else {
            LOG.debug("feed '{}': afterCommit hook completed", feedId);
        }
    }

    @Override
    public void pageProcessed(String feedId, FeedPageMetadata pageMetadata) {
        LOG.debug("feed '{}': page '{}' processed", feedId, pageMetadata.href(FeedPageRel.SELF));
    }

    @Override
    public void endOfFeedReached(String feedId) {
        // how the run ended is a detail: runCompleted follows immediately anyway
        LOG.debug("feed '{}': head reached (no younger events)", feedId);
    }

    @Override
    public void runInterrupted(String feedId, FeedRunResult result) {
        LOG.info("feed '{}': run interrupted; {} read, {} accepted, {} processed — the next run resumes the rest",
                feedId, result.read(), result.accepted(), result.processed());
    }

    @Override
    public void runCompleted(String feedId, FeedRunResult result) {
        // the counters are only news when there was something to count; the FeedRunner logs the
        // completion (with the next poll moment) at INFO for every run
        if (result.read() > 0) {
            LOG.info("feed '{}': run completed; {} read, {} accepted, {} processed",
                    feedId, result.read(), result.accepted(), result.processed());
        } else {
            LOG.debug("feed '{}': run completed; 0 read, 0 accepted, 0 processed", feedId);
        }
    }

    @Override
    public void runFailed(FeedRunFailure failure) {
        // the ERROR log (incl. stack trace + backoff) already lives in FeedRunner; here a concise WARN event trail
        LOG.warn("feed '{}': run failed (attempt {}, next attempt after {})",
                failure.feedId(), failure.consecutiveFailures(), failure.nextAttemptAfter());
    }
}
