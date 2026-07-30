package be.wegenenverkeer.atomium.client.handler;

import java.time.Duration;

/**
 * Determines how long the {@link FeedRunner} waits before a new attempt after consecutive failed runs. The backoff
 * works <em>schedule-based</em> (no sleeping thread): the scheduler keeps ticking frequently, but the runner
 * refuses to start until the next attempt time has been reached.
 *
 * <p>Configurable per feed via {@link Feed.Builder#backoffPolicy(FeedBackoffPolicy)}; the default is the {@link ExponentialFeedBackoffPolicy}.
 */
@FunctionalInterface
public interface FeedBackoffPolicy {

    /**
     * The wait time before the next attempt.
     *
     * @param consecutiveFailures the number of consecutive failed runs, {@code >= 1} (1 = the first failure)
     */
    Duration nextInterval(int consecutiveFailures);
}
