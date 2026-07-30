package be.wegenenverkeer.atomium.client.fetch;

import be.wegenenverkeer.atomium.client.protocol.AtomiumEntry;

/**
 * One fetched entry, together with the pointer from which to continue reading after this entry.
 *
 * <p>The {@link #nextFeedPointer()} exists per entry (not just per {@link FetchResult}) because many
 * feed consumers process events <strong>one by one</strong> and are not idempotent. By advancing the
 * pointer to {@code nextFeedPointer} after each processed event (and persisting it together with the
 * effect of the event in the same transaction), no already processed event is ever offered again after
 * a crash.
 */
public record FetchEntry(AtomiumEntry entry, FeedPointer nextFeedPointer) {
}
