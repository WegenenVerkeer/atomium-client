package be.wegenenverkeer.atomium.client.handler;

import be.wegenenverkeer.atomium.client.fetch.FeedPointer;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Non-persistent {@link FeedPointerRepository} on an in-memory map: the default of the {@link Feed.Builder},
 * for tests and demos. The feed pointer does <em>not</em> survive a restart — after a restart the feed starts
 * over from its initial pointer. For production a real implementation belongs here
 * (e.g. a JDBC implementation).
 */
public final class InMemoryFeedPointerRepository implements FeedPointerRepository {

    private final Map<String, FeedPointer> pointers = new ConcurrentHashMap<>();

    @Override
    public Optional<FeedPointer> find(String feedId) {
        return Optional.ofNullable(pointers.get(feedId));
    }

    @Override
    public void save(String feedId, FeedPointer feedPointer) {
        pointers.put(feedId, feedPointer);
    }
}
