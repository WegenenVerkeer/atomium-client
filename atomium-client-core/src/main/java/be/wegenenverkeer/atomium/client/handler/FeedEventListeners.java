package be.wegenenverkeer.atomium.client.handler;

import be.wegenenverkeer.atomium.client.fetch.FeedPointer;
import be.wegenenverkeer.atomium.client.protocol.FeedPageMetadata;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.function.Consumer;

/**
 * Composite that fans out the events of one feed to all its {@link FeedEventListener}s. This is where the hard rule
 * lives that <b>a listener must never break a run</b>: every callback runs in its own try/catch, an exception is
 * logged (ERROR) and otherwise ignored. The feed processing only talks to this composite, never to individual
 * listeners.
 */
class FeedEventListeners implements FeedEventListener {

    private static final Logger LOG = LoggerFactory.getLogger(FeedEventListeners.class);

    private final List<FeedEventListener> listeners;

    FeedEventListeners(List<FeedEventListener> listeners) {
        this.listeners = listeners;
    }

    @Override
    public void feedActivated(String feedId) {
        dispatch(feedId, listener -> listener.feedActivated(feedId));
    }

    @Override
    public void feedDeactivated(String feedId) {
        dispatch(feedId, listener -> listener.feedDeactivated(feedId));
    }

    @Override
    public void runStarted(String feedId, FeedPointer startPosition) {
        dispatch(feedId, listener -> listener.runStarted(feedId, startPosition));
    }

    @Override
    public void pageFetched(String feedId, FeedPageMetadata pageMetadata, int entryCount) {
        dispatch(feedId, listener -> listener.pageFetched(feedId, pageMetadata, entryCount));
    }

    @Override
    public void feedNotModified(String feedId) {
        dispatch(feedId, listener -> listener.feedNotModified(feedId));
    }

    @Override
    public void feedPointerAdvanced(String feedId, FeedPointer feedPointer, FeedRunResult sincePreviousCommit,
                                    @Nullable OffsetDateTime latestEventUpdated) {
        dispatch(feedId, listener ->
                listener.feedPointerAdvanced(feedId, feedPointer, sincePreviousCommit, latestEventUpdated));
    }

    @Override
    public void pageProcessed(String feedId, FeedPageMetadata pageMetadata) {
        dispatch(feedId, listener -> listener.pageProcessed(feedId, pageMetadata));
    }

    @Override
    public void endOfFeedReached(String feedId) {
        dispatch(feedId, listener -> listener.endOfFeedReached(feedId));
    }

    @Override
    public void runInterrupted(String feedId, FeedRunResult result) {
        dispatch(feedId, listener -> listener.runInterrupted(feedId, result));
    }

    @Override
    public void runCompleted(String feedId, FeedRunResult result) {
        dispatch(feedId, listener -> listener.runCompleted(feedId, result));
    }

    @Override
    public void runFailed(FeedRunFailure failure) {
        dispatch(failure.feedId(), listener -> listener.runFailed(failure));
    }

    private void dispatch(String feedId, Consumer<FeedEventListener> event) {
        for (FeedEventListener listener : listeners) {
            try {
                event.accept(listener);
            } catch (RuntimeException e) {
                // ERROR, not WARN: a throwing listener is always unexpected — "never break a run" is about
                // control flow, not severity
                LOG.error("feed '{}': FeedEventListener {} threw an exception; ignored (a listener must not break a run)",
                        feedId, listener.getClass().getName(), e);
            }
        }
    }
}
