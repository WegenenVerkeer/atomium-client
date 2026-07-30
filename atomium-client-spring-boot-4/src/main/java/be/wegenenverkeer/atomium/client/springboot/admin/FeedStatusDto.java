package be.wegenenverkeer.atomium.client.springboot.admin;

import org.jspecify.annotations.Nullable;

import java.time.OffsetDateTime;

/**
 * The lifecycle status of one feed, including the backoff state — exactly what support wants to see when a feed
 * seems "silent": is it in backoff after consecutive failures, and when is the next run due?
 *
 * @param feedId                    the feed
 * @param active                    the desired state: may the scheduler start runs? (config {@code active-on-startup} + admin)
 * @param running                   the actual state: is a run in progress right now?
 * @param consecutiveFailures the number of consecutive failed runs since the last successful run (0 = none)
 * @param nextRun               the earliest time the next run may start (the next poll, or after failures the
 *                                  backoff deadline) in the local zone, or {@code null} if the very next
 *                                  scheduler tick may start immediately
 */
public record FeedStatusDto(String feedId, boolean active, boolean running,
                            int consecutiveFailures, @Nullable OffsetDateTime nextRun) {
}
