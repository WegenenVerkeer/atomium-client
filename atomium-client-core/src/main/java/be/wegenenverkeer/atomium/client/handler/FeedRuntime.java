package be.wegenenverkeer.atomium.client.handler;

import be.wegenenverkeer.atomium.client.fetch.FeedPointer;
import org.jspecify.annotations.Nullable;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

/**
 * The running machinery of one {@link Feed}, assembled by {@link #of(Feed)}: the {@link FeedRunner}
 * (lifecycle, backoff) around a consumer that walks the feed, and the {@link EntryPusher} to process a standalone
 * content item. The per-feed entry point for a scheduler and management tooling (e.g. an admin endpoint).
 */
public final class FeedRuntime {

    private final Feed<?> feed;
    private final FeedRunner runner;
    private final EntryPusher pusher;
    private final Progress progress;

    FeedRuntime(Feed<?> feed, FeedRunner runner, EntryPusher pusher, Progress progress) {
        this.feed = feed;
        this.runner = runner;
        this.pusher = pusher;
        this.progress = progress;
    }

    /** Assembles the runtime of a {@link Feed} (fail-fast on an inconsistent definition). */
    public static FeedRuntime of(Feed<?> feed) {
        return of(feed, Clock.systemDefaultZone());
    }

    /** Like {@link #of(Feed)}, with a custom {@link Clock} for the backoff timing (tests). */
    public static FeedRuntime of(Feed<?> feed, Clock clock) {
        return assemble(feed, clock);
    }

    private static <T> FeedRuntime assemble(Feed<T> feed, Clock clock) {
        String feedId = feed.feedId();
        Progress progress = new Progress(clock);
        // the progress listener first: that way the runtime state is already updated before the app listeners fire
        List<FeedEventListener> allListeners = new ArrayList<>();
        allListeners.add(progress);
        allListeners.addAll(feed.listeners());
        FeedEventListener listeners = new FeedEventListeners(allListeners);
        Supplier<FeedHandlerController<T>> controllers =
                controllerFor(feedId, feed.handler(), feed.preferredBatchSize(), feed.maxUnflushedPages());
        FeedConsumerImpl<T> consumer = new FeedConsumerImpl<>(feedId, feed.handler(), controllers,
                feed.atomiumClient(), feed.contentDecoder(), feed.pointerRepository(), feed.transactions(),
                initialFeedPointer(feed), listeners);
        FeedRunner runner = new FeedRunner(feedId, feed.queryInterval(), consumer, feed.executor(),
                feed.activeOnStartup(), feed.backoffPolicy(), clock, listeners);
        // the consumer is also the EntryPusher (it owns the decoder + handler + transaction)
        return new FeedRuntime(feed, runner, consumer, progress);
    }

    /**
     * The start-position strategy of the feed, or — without an initial pointer — the fail-fast that a pointer
     * must then already be persisted. That check is only a read on the repository; the strategy itself is only
     * consulted lazily (on the first run), so that a possible HTTP call to the source feed does not block
     * assembly.
     */
    private static Supplier<FeedPointer> initialFeedPointer(Feed<?> feed) {
        Supplier<FeedPointer> initial = feed.initialFeedPointer();
        if (initial != null) {
            return initial;
        }
        // core assertion (framework-neutral); an assembly layer additionally validates this on its own
        // configuration, in its own terminology — deliberately two checks, each with a different purpose
        if (feed.pointerRepository().find(feed.feedId()).isEmpty()) {
            throw new IllegalStateException(("feed '%s' has no persisted feed pointer and no initial "
                    + "feed pointer; supply an initialFeedPointer").formatted(feed.feedId()));
        }
        // There is a pointer in the repo → this supplier is never consulted; fails with a clear message if it is consulted anyway.
        return () -> {
            throw new IllegalStateException("feed '%s' has no initial feed pointer".formatted(feed.feedId()));
        };
    }

    /**
     * Picks, based on the handler type, the {@link FeedHandlerController} that offers the entries to the handler.
     * There is only one: everything goes through the {@link BatchedFeedHandlerController}. An {@link EntryFeedHandler}
     * is adapted to a batch of 1 for that purpose — "process per entry" is the special case of batching.
     *
     * <p>A {@link Supplier}, because the consumer takes a fresh controller per run (a half-filled batch must never
     * linger between runs).
     *
     * <p><b>Fail-fast at assembly</b> (twice): a {@link FeedHandler} that implements neither variant has no
     * entry callback at all — that feed would silently do nothing. And a {@code preferredBatchSize} on a feed with an
     * {@link EntryFeedHandler} is a configuration mistake (it processes per entry); we prefer refusing it over silently
     * ignoring it.
     */
    static <T> Supplier<FeedHandlerController<T>> controllerFor(
            String feedId, FeedHandler<T> handler, @Nullable Integer preferredBatchSize, int maxUnflushedPages) {

        int threshold;
        BatchedFeedHandler<T> batchedHandler;
        switch (handler) {
            case BatchedFeedHandler<T> batched -> {
                batchedHandler = batched;
                threshold = preferredBatchSize != null ? preferredBatchSize : FeedDefaults.PREFERRED_BATCH_SIZE;
            }
            case EntryFeedHandler<T> perEntry -> {
                if (preferredBatchSize != null) {
                    // core assertion (framework-neutral); an assembly layer additionally validates this on its
                    // own configuration, in its own terminology — deliberately two checks, each with a different purpose
                    throw new IllegalStateException(("feed '%s': a preferredBatchSize is set, but handler %s "
                            + "is an EntryFeedHandler (processes per entry, batch size is always 1). Remove the "
                            + "preferredBatchSize, or implement BatchedFeedHandler.")
                            .formatted(feedId, handler.getClass().getName()));
                }
                batchedHandler = new EntryFeedHandlerAdapter<>(feedId, perEntry);
                threshold = 1;
            }
            default -> throw new IllegalStateException(
                    ("feed '%s': handler %s implements only FeedHandler itself and thus has no entry callback; "
                            + "implement EntryFeedHandler or BatchedFeedHandler")
                            .formatted(feedId, handler.getClass().getName()));
        }
        int size = threshold;
        return () -> new BatchedFeedHandlerController<>(batchedHandler, size, maxUnflushedPages);
    }

    /** The definition this runtime was assembled from. */
    public Feed<?> feed() {
        return feed;
    }

    public FeedRunner runner() {
        return runner;
    }

    public EntryPusher pusher() {
        return pusher;
    }

    public String feedId() {
        return runner.feedId();
    }

    /** The moment of the last commit (feed pointer persisted), or {@code null} before the first one. */
    public @Nullable OffsetDateTime lastCommit() {
        return progress.lastCommit;
    }

    /** The {@code updated} timestamp of the most recent processed event, or {@code null} while nothing has been processed. */
    public @Nullable OffsetDateTime lastEvent() {
        return progress.lastEvent;
    }

    /**
     * The internal progress listener: tracks when the last commit happened and how fresh the most recent
     * processed event is, so that management tooling (health, admin) can tell a quiet feed from a dead one.
     */
    static final class Progress implements FeedEventListener {

        private final Clock clock;
        private volatile @Nullable OffsetDateTime lastCommit;
        private volatile @Nullable OffsetDateTime lastEvent;

        Progress(Clock clock) {
            this.clock = clock;
        }

        @Override
        public void feedPointerAdvanced(String feedId, FeedPointer feedPointer, FeedRunResult sincePreviousCommit) {
            lastCommit = OffsetDateTime.now(clock);
        }

        @Override
        public void entriesProcessed(String feedId, List<? extends BatchEntry<?>> entries) {
            entries.stream()
                    .map(batchEntry -> batchEntry.entry().updated())
                    .max(Comparator.naturalOrder())
                    .ifPresent(mostRecent -> lastEvent = mostRecent);
        }
    }
}
