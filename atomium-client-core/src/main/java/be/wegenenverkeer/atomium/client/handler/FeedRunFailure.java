package be.wegenenverkeer.atomium.client.handler;

import org.jspecify.annotations.Nullable;

import java.time.OffsetDateTime;

/**
 * The payload of {@link FeedEventListener#runFailed}: a run has failed. Carries enough context for an
 * alerting listener to decide for itself when to alert (e.g. only after N consecutive failures).
 *
 * @param feedId                    the feed
 * @param cause                   the failure (for an entry failure: the decode/handler exception itself, not the wrapper)
 * @param consecutiveFailures the number of consecutive failed runs, {@code >= 1}
 * @param nextAttemptAfter          the earliest moment at which the runner allows a new attempt (backoff deadline),
 *                                  in the local zone and at second resolution
 * @param entryId                   the entry at which things went wrong, or {@code null} if the failure has no entry
 *                                  context (e.g. the source unreachable while fetching a page)
 * @param phase                      the phase ({@code DECODE}/{@code HANDLER}) for an entry failure, otherwise {@code null}
 */
public record FeedRunFailure(String feedId, RuntimeException cause, int consecutiveFailures,
                          OffsetDateTime nextAttemptAfter, @Nullable String entryId, @Nullable FeedEntryPhase phase) {
}
