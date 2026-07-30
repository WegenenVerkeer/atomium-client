package be.wegenenverkeer.atomium.client.handler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Supplies each feed with its own single-thread executor with a recognizable thread name
 * ({@code atomium-feed-<feedId>}). Feeds thus run fully independently, and the single thread additionally serializes
 * consecutive runs of the same feed (an extra safety net in addition to the one-run guarantee in {@link FeedRunner}).
 *
 * <p>A reusable helper object with a lifecycle: keep one instance around and call {@link #shutdown()} when shutting
 * down; to use a different executor, set it on the feed via {@link Feed.Builder#executor(java.util.concurrent.Executor)}.
 */
public final class PerFeedThreadExecutors {

    private final Map<String, ExecutorService> executors = new ConcurrentHashMap<>();

    public Executor executorFor(String feedId) {
        return executors.computeIfAbsent(feedId, id -> Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "atomium-feed-" + id);
            thread.setDaemon(true);
            return thread;
        }));
    }

    /** Winds down all per-feed threads cleanly (invoked at context shutdown). */
    public void shutdown() {
        executors.values().forEach(ExecutorService::shutdown);
        for (ExecutorService executor : executors.values()) {
            try {
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
