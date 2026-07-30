package be.wegenenverkeer.atomium.client.fetch;

import be.wegenenverkeer.atomium.client.protocol.FeedPageMetadata;

import java.util.List;

/**
 * The result of a successful fetch.
 *
 * @param fetchEntries        the delivered events, in <strong>oldest-first</strong> order and already
 *                            deduplicated (see {@link FetchCoordinate#filterEventId()}). May be empty,
 *                            e.g. when a server without etag support returns an unchanged page
 * @param nextFeedPointer the pointer to perform the next fetch as efficiently as possible
 * @param feedPageMetadata    the complete metadata of the fetched page (links, id, updated, …), so that
 *                            nothing of the protocol stays hidden
 * @param feedHasMorePages {@code true} when the fetched page was complete and a younger page
 *                            therefore exists: the consumer can immediately {@code fetch} again.
 *                            {@code false} when the live head has been reached and polling should
 *                            happen later. Analogous to {@link java.util.Iterator#hasNext()}
 */
public record FetchResult(
        List<FetchEntry> fetchEntries,
        FeedPointer nextFeedPointer,
        FeedPageMetadata feedPageMetadata,
        boolean feedHasMorePages
) {
}
