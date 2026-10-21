package be.wegenenverkeer.atomium.client.exception;

import java.time.Duration;

/**
 * {@code FeedRuntime.whileStopped} could not get the feed stopped: the run in flight did not stop within the
 * timeout ({@link #timedOut}) or the wait was interrupted ({@link #interrupted}). Nothing was changed.
 */
public class FeedStillRunningException extends AtomiumClientException {

    private FeedStillRunningException(String message) {
        super(message);
    }

    public static FeedStillRunningException timedOut(String feedId, Duration timeout) {
        return new FeedStillRunningException(("feed '%s': the run in flight did not stop within %s; nothing was "
                + "changed and the feed is back in its previous state. Retry later.").formatted(feedId, timeout));
    }

    public static FeedStillRunningException interrupted(String feedId) {
        return new FeedStillRunningException(("feed '%s': interrupted while waiting for the run in flight to stop; "
                + "nothing was changed and the feed is back in its previous state.").formatted(feedId));
    }
}
