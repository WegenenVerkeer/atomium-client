package be.wegenenverkeer.atomium.client.handler;

import be.wegenenverkeer.atomium.client.fetch.AtomiumClient;
import be.wegenenverkeer.atomium.client.fetch.FeedPointer;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * The definition of one feed to consume: the application's {@link FeedHandler}, the implementations of the
 * building blocks ({@link AtomiumClient}, {@link FeedContentDecoder}, {@link FeedPointerRepository},
 * {@link FeedTransactions}) and the configuration of the processing. Build one with {@link #builder};
 * {@link FeedRuntime#of(Feed)} assembles the running machinery from it.
 *
 * <p>Whatever <em>cannot</em> have a default is a parameter of {@link #builder}; everything else has one (see the
 * builder methods). Note: the defaults for {@link Builder#pointerRepository} and {@link Builder#transactions}
 * are <em>non-persistent</em> — fine for tests and demos, but in production you supply real implementations.
 *
 * @param <T> the content type of the entries, as produced by the {@link FeedContentDecoder} and processed by the
 *            {@link FeedHandler}
 */
public final class Feed<T> {

    private final String feedId;
    private final FeedHandler<T> handler;
    private final AtomiumClient atomiumClient;
    private final FeedContentDecoder<T> contentDecoder;
    private final FeedPointerRepository pointerRepository;
    private final FeedTransactions transactions;
    private final @Nullable Supplier<FeedPointer> initialFeedPointer;
    private final FeedBackoffPolicy backoffPolicy;
    private final Executor executor;
    private final List<FeedEventListener> listeners;
    private final Duration queryInterval;
    private final boolean activeOnStartup;
    private final @Nullable Integer preferredProcessingSize;
    private final int maxUncommittedPages;

    private Feed(Builder<T> builder, Executor executor) {
        this.feedId = builder.feedId;
        this.handler = builder.handler;
        this.atomiumClient = builder.atomiumClient;
        this.contentDecoder = builder.contentDecoder;
        this.pointerRepository = builder.pointerRepository;
        this.transactions = builder.transactions;
        this.initialFeedPointer = builder.initialFeedPointer;
        this.backoffPolicy = builder.backoffPolicy;
        this.executor = executor;
        this.listeners = List.copyOf(builder.listeners);
        this.queryInterval = builder.queryInterval;
        this.activeOnStartup = builder.activeOnStartup;
        this.preferredProcessingSize = builder.preferredProcessingSize;
        this.maxUncommittedPages = builder.maxUncommittedPages;
    }

    public static <T> Builder<T> builder(String feedId, FeedHandler<T> handler, AtomiumClient atomiumClient,
                                         FeedContentDecoder<T> contentDecoder) {
        return new Builder<>(feedId, handler, atomiumClient, contentDecoder);
    }

    public String feedId() {
        return feedId;
    }

    public FeedHandler<T> handler() {
        return handler;
    }

    public AtomiumClient atomiumClient() {
        return atomiumClient;
    }

    public FeedContentDecoder<T> contentDecoder() {
        return contentDecoder;
    }

    public FeedPointerRepository pointerRepository() {
        return pointerRepository;
    }

    public FeedTransactions transactions() {
        return transactions;
    }

    /** The start position for a brand-new feed, or {@code null} if none is configured. */
    public @Nullable Supplier<FeedPointer> initialFeedPointer() {
        return initialFeedPointer;
    }

    public FeedBackoffPolicy backoffPolicy() {
        return backoffPolicy;
    }

    public Executor executor() {
        return executor;
    }

    public List<FeedEventListener> listeners() {
        return listeners;
    }

    public Duration queryInterval() {
        return queryInterval;
    }

    public boolean activeOnStartup() {
        return activeOnStartup;
    }

    /** The number of accepted entries at which a batch is processed, or {@code null} for the default. */
    public @Nullable Integer preferredProcessingSize() {
        return preferredProcessingSize;
    }

    public int maxUncommittedPages() {
        return maxUncommittedPages;
    }

    /**
     * Builds a {@link Feed}. Whatever cannot have a default is a parameter of
     * {@link Feed#builder(String, FeedHandler, AtomiumClient, FeedContentDecoder)}; everything else has one.
     */
    public static final class Builder<T> {

        private final String feedId;
        private final FeedHandler<T> handler;
        private final AtomiumClient atomiumClient;
        private final FeedContentDecoder<T> contentDecoder;
        private FeedPointerRepository pointerRepository = new InMemoryFeedPointerRepository();
        private FeedTransactions transactions = FeedTransactions.withoutTransactions();
        private @Nullable Supplier<FeedPointer> initialFeedPointer;
        private FeedBackoffPolicy backoffPolicy =
                new ExponentialFeedBackoffPolicy(Duration.ofMinutes(1), Duration.ofHours(1), 2);
        private @Nullable Executor executor;
        private final List<FeedEventListener> listeners = new ArrayList<>();
        private Duration queryInterval = Duration.ofMinutes(1);
        private boolean activeOnStartup = false;
        private @Nullable Integer preferredProcessingSize;
        private int maxUncommittedPages = FeedDefaults.MAX_UNCOMMITTED_PAGES;

        private Builder(String feedId, FeedHandler<T> handler, AtomiumClient atomiumClient,
                        FeedContentDecoder<T> contentDecoder) {
            if (feedId == null || feedId.isBlank()) {
                throw new IllegalArgumentException("feedId is missing or blank");
            }
            this.feedId = feedId;
            this.handler = Objects.requireNonNull(handler, "handler");
            this.atomiumClient = Objects.requireNonNull(atomiumClient, "atomiumClient");
            this.contentDecoder = Objects.requireNonNull(contentDecoder, "contentDecoder");
        }

        /**
         * Persists the feed pointer (the read position) of this feed. Default: an
         * {@link InMemoryFeedPointerRepository} — non-persistent, so after a restart the feed starts over
         * from its initial pointer; in production you supply a real implementation.
         */
        public Builder<T> pointerRepository(FeedPointerRepository pointerRepository) {
            this.pointerRepository = Objects.requireNonNull(pointerRepository, "pointerRepository");
            return this;
        }

        /**
         * The transactions within which the handler effect and the feed pointer are committed together. Default:
         * {@link FeedTransactions#withoutTransactions()} — without the guarantee that effect and pointer commit
         * together; with transactional persistence you supply a real implementation.
         */
        public Builder<T> transactions(FeedTransactions transactions) {
            this.transactions = Objects.requireNonNull(transactions, "transactions");
            return this;
        }

        /**
         * The start position for a brand-new feed; only consulted (lazily, on the first run) as long as the
         * {@link FeedPointerRepository} has no pointer yet. Useful strategies:
         * {@link AtomiumClient#pointerToOldest()}, {@link AtomiumClient#pointerFromNow()} or
         * {@code () -> new FeedPointer(pageLink)}. Without an initial pointer there must already be a
         * persisted pointer (otherwise assembly fails fast).
         */
        public Builder<T> initialFeedPointer(@Nullable Supplier<FeedPointer> initialFeedPointer) {
            this.initialFeedPointer = initialFeedPointer;
            return this;
        }

        /** The backoff on consecutive failed runs. Default: exponential 1m → 1h, factor 2. */
        public Builder<T> backoffPolicy(FeedBackoffPolicy backoffPolicy) {
            this.backoffPolicy = Objects.requireNonNull(backoffPolicy, "backoffPolicy");
            return this;
        }

        /**
         * The {@link Executor} on which the runs of this feed execute. Default: a dedicated daemon thread
         * ({@code atomium-feed-<feedId>}); it also serializes consecutive runs of the same feed.
         * Note: that default thread is <em>unmanaged</em> — there is no shutdown path (daemon, so the JVM
         * simply exits, but every re-{@code build()} starts a new thread). If you want managed shutdown,
         * supply a {@link PerFeedThreadExecutors#executorFor(String) PerFeedThreadExecutors} executor
         * or an executor of your own here.
         */
        public Builder<T> executor(Executor executor) {
            this.executor = Objects.requireNonNull(executor, "executor");
            return this;
        }

        /** Add a {@link FeedEventListener} (additive; default no listeners). */
        public Builder<T> addListener(FeedEventListener listener) {
            listeners.add(Objects.requireNonNull(listener, "listener"));
            return this;
        }

        /** Add {@link FeedEventListener}s (additive; default no listeners). */
        public Builder<T> addListeners(List<FeedEventListener> listeners) {
            listeners.forEach(this::addListener);
            return this;
        }

        /**
         * The poll frequency: the wait time between the end of a successful run and the start of the next
         * (must be positive). Default: 1 minute.
         */
        public Builder<T> queryInterval(Duration queryInterval) {
            Objects.requireNonNull(queryInterval, "queryInterval");
            if (queryInterval.isZero() || queryInterval.isNegative()) {
                throw new IllegalArgumentException(
                        "feed '%s': queryInterval must be positive, was %s".formatted(feedId, queryInterval));
            }
            this.queryInterval = queryInterval;
            return this;
        }

        /** Whether the feed is active right away. Default: {@code false} (activation then happens explicitly). */
        public Builder<T> activeOnStartup(boolean activeOnStartup) {
            this.activeOnStartup = activeOnStartup;
            return this;
        }

        /**
         * The number of accepted entries at which a batch is processed. Only meaningful with a
         * {@link SimpleBatchedProcessingFeedHandler} (default: {@value FeedDefaults#PREFERRED_PROCESSING_SIZE});
         * set on an {@link EntryFeedHandler}, assembly fails fast.
         */
        public Builder<T> preferredProcessingSize(@Nullable Integer preferredProcessingSize) {
            if (preferredProcessingSize != null && preferredProcessingSize < 1) {
                throw new IllegalArgumentException(
                        "feed '%s': preferredProcessingSize must be at least 1, was %d".formatted(feedId, preferredProcessingSize));
            }
            this.preferredProcessingSize = preferredProcessingSize;
            return this;
        }

        /**
         * The safety net: once this many pages have been read without a commit, every boundary asks the
         * processing to wrap up (even a partial batch), so the window a crash would have to re-read stays
         * bounded. Default: {@value FeedDefaults#MAX_UNCOMMITTED_PAGES}.
         */
        public Builder<T> maxUncommittedPages(int maxUncommittedPages) {
            if (maxUncommittedPages < 1) {
                // < 1 would silently degrade to a wrap-up on every page boundary — exactly what the safety net avoids
                throw new IllegalArgumentException(
                        "feed '%s': maxUncommittedPages must be at least 1, was %d"
                                .formatted(feedId, maxUncommittedPages));
            }
            this.maxUncommittedPages = maxUncommittedPages;
            return this;
        }

        public Feed<T> build() {
            Executor executor = this.executor != null ? this.executor : defaultExecutor(feedId);
            return new Feed<>(this, executor);
        }

        private static Executor defaultExecutor(String feedId) {
            return Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "atomium-feed-" + feedId);
                thread.setDaemon(true);
                return thread;
            });
        }
    }
}
