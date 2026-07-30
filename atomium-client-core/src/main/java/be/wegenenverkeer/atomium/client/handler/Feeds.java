package be.wegenenverkeer.atomium.client.handler;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Registry of the {@link FeedRuntime}s, one per assembled feed. The scheduler and management tooling (e.g. an
 * admin endpoint) talk to the feeds through this registry. Sorted by {@code feedId} so that the order
 * (in the admin output, among others) is deterministic and logical.
 */
public final class Feeds {

    private final Map<String, FeedRuntime> feeds = new TreeMap<>();

    /**
     * @throws IllegalStateException on two feeds with the same feedId (almost certainly a copy-paste error in
     *                               {@code getFeedId()}) — fail-fast instead of silently letting one disappear
     */
    public Feeds(List<FeedRuntime> feeds) {
        for (FeedRuntime feed : feeds) {
            FeedRuntime existing = this.feeds.putIfAbsent(feed.feedId(), feed);
            if (existing != null) {
                throw new IllegalStateException(
                        "duplicate feedId '%s': two feeds share the same FeedHandler.getFeedId()".formatted(feed.feedId()));
            }
        }
    }

    public FeedRuntime get(String feedId) {
        var feed = feeds.get(feedId);
        if (feed == null) {
            throw new IllegalArgumentException(
                    "no registered feed for '%s'; known feeds: %s".formatted(feedId, feeds.keySet()));
        }
        return feed;
    }

    public Collection<FeedRuntime> all() {
        return feeds.values();
    }

    /** Deactivate all feeds, so that runs in flight stop cleanly after their next commit point (at shutdown). */
    public void close() {
        feeds.values().forEach(feed -> feed.runner().deactivate());
    }
}
