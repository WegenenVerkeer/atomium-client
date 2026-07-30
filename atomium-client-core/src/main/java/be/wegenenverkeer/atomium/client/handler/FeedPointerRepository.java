package be.wegenenverkeer.atomium.client.handler;

import be.wegenenverkeer.atomium.client.fetch.FeedPointer;

import java.util.Optional;

/**
 * Persists, per feed (keyed by {@link FeedHandler#getFeedId() feedId}), the last processed {@link FeedPointer}.
 *
 * <p>This is an extension point: supply an implementation on your own persistence (e.g. JDBC); the default of the
 * {@link Feed.Builder} is the non-persistent {@link InMemoryFeedPointerRepository}.
 */
public interface FeedPointerRepository {

    /**
     * The stored read position for this feed, if present.
     */
    Optional<FeedPointer> find(String feedId);

    /**
     * Write (upsert) the read position for this feed.
     */
    void save(String feedId, FeedPointer feedPointer);
}
