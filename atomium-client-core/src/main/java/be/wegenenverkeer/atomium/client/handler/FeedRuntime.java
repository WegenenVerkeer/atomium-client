package be.wegenenverkeer.atomium.client.handler;

import be.wegenenverkeer.atomium.client.fetch.FeedPointer;
import org.jspecify.annotations.Nullable;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
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
        RunnerProgressListener runnerProgress = new RunnerProgressListener();
        List<FeedEventListener> allListeners = new ArrayList<>();
        allListeners.add(progress);
        allListeners.add(runnerProgress);
        allListeners.addAll(feed.listeners());
        FeedEventListener listeners = new FeedEventListeners(allListeners);
        Supplier<FeedProcessor<T>> processors =
                processorFor(feedId, feed.handler(), feed.maxProcessingSize());
        FeedConsumerImpl<T> consumer = new FeedConsumerImpl<>(feedId, feed.handler(), processors,
                feed.maxUncommittedPages(), feed.atomiumClient(), feed.contentDecoder(),
                feed.pointerRepository(), feed.transactions(), initialFeedPointer(feed), listeners);
        FeedRunner runner = new FeedRunner(feedId, feed.queryInterval(), consumer, feed.executor(),
                feed.activeOnStartup(), feed.backoffPolicy(), clock, listeners);
        runnerProgress.runner = runner;
        // the consumer is also the EntryPusher (it owns the decoder + handler + transaction)
        return new FeedRuntime(feed, runner, consumer, progress);
    }

    /**
     * Reports every commit to the {@link FeedRunner} ({@link FeedRunner#noteProgress()}), so that a run that
     * commits work ends the failure streak even if it fails later on. A listener (set after construction —
     * the runner itself needs the listener composite), because the consumer only knows the listeners.
     */
    private static final class RunnerProgressListener implements FeedEventListener {

        private @Nullable FeedRunner runner;

        @Override
        public void feedPointerAdvanced(String feedId, FeedPointer feedPointer, FeedRunResult sincePreviousCommit,
                                        @Nullable OffsetDateTime latestEventUpdated) {
            if (runner != null) {
                runner.noteProgress();
            }
        }
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
     * Picks, based on the handler type, the {@link FeedProcessor} the consumer runs on. A {@link Supplier},
     * because the consumer takes a fresh processor per run (a half-filled batch must never linger between
     * runs).
     *
     * <p><b>Fail-fast at assembly</b> (three times): a {@link FeedHandler} that implements neither variant has
     * no entry callback at all — that feed would silently do nothing. A handler that implements <em>both</em>
     * variants is ambiguous — the framework refuses to silently pick one. And a {@code maxProcessingSize} on a
     * feed with an {@link EntryFeedHandler} is a configuration mistake (it processes per entry); we prefer
     * refusing it over silently ignoring it.
     */
    static <T> Supplier<FeedProcessor<T>> processorFor(
            String feedId, FeedHandler<T> handler, @Nullable Integer maxProcessingSize) {

        if (handler instanceof SimpleProcessingFeedHandler && handler instanceof EntryFeedHandler) {
            // core assertion (framework-neutral); the switch below would otherwise silently pick one variant
            throw new IllegalStateException(("feed '%s': handler %s implements both EntryFeedHandler and "
                    + "SimpleProcessingFeedHandler; the framework cannot choose which callback drives the feed. "
                    + "Implement exactly one variant.").formatted(feedId, handler.getClass().getName()));
        }
        switch (handler) {
            case SimpleProcessingFeedHandler<T, ?> simple -> {
                int size = maxProcessingSize != null
                        ? maxProcessingSize : FeedDefaults.MAX_PROCESSING_SIZE;
                return () -> new SimpleFeedProcessor<>(simple, size);
            }
            case EntryFeedHandler<T> perEntry -> {
                if (maxProcessingSize != null) {
                    // core assertion (framework-neutral); an assembly layer additionally validates this on its
                    // own configuration, in its own terminology — deliberately two checks, each with a different purpose
                    throw new IllegalStateException(("feed '%s': a maxProcessingSize is set, but handler %s "
                            + "is an EntryFeedHandler (processes per entry, the processing size is always 1). "
                            + "Remove the maxProcessingSize, or implement SimpleProcessingFeedHandler.")
                            .formatted(feedId, handler.getClass().getName()));
                }
                return () -> new EntryFeedProcessor<>(feedId, perEntry);
            }
            default -> throw new IllegalStateException(
                    ("feed '%s': handler %s implements only FeedHandler itself and thus has no entry callback; "
                            + "implement EntryFeedHandler or SimpleProcessingFeedHandler")
                            .formatted(feedId, handler.getClass().getName()));
        }
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

    /**
     * The {@code updated} timestamp of the youngest event a commit has advanced the pointer past, or
     * {@code null} while no commit has covered an event.
     */
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
        public void feedPointerAdvanced(String feedId, FeedPointer feedPointer, FeedRunResult sincePreviousCommit,
                                        @Nullable OffsetDateTime latestEventUpdated) {
            lastCommit = OffsetDateTime.now(clock);
            if (latestEventUpdated != null) {
                lastEvent = latestEventUpdated;
            }
        }
    }
}
