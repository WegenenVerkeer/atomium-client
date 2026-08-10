package be.wegenenverkeer.atomium.client.springboot;

import be.wegenenverkeer.atomium.client.handler.SimpleProcessingFeedHandler;
import be.wegenenverkeer.atomium.client.handler.ExponentialFeedBackoffPolicy;
import be.wegenenverkeer.atomium.client.handler.Feed;
import be.wegenenverkeer.atomium.client.handler.FeedBackoffPolicy;
import be.wegenenverkeer.atomium.client.handler.FeedContentDecoder;
import be.wegenenverkeer.atomium.client.handler.FeedEventListener;
import be.wegenenverkeer.atomium.client.handler.FeedHandler;
import be.wegenenverkeer.atomium.client.handler.FeedPointerRepository;
import be.wegenenverkeer.atomium.client.handler.FeedRuntime;
import be.wegenenverkeer.atomium.client.handler.FeedTransactions;
import be.wegenenverkeer.atomium.client.handler.PerFeedThreadExecutors;

import be.wegenenverkeer.atomium.client.fetch.AtomiumClient;
import be.wegenenverkeer.atomium.client.fetch.FeedPointer;
import be.wegenenverkeer.atomium.client.jackson.JacksonFeedContentDecoder;
import be.wegenenverkeer.atomium.client.jackson.JacksonFeedPageDecoder;
import be.wegenenverkeer.atomium.client.port.FeedHttpClient;
import be.wegenenverkeer.atomium.client.port.FeedPageDecoder;
import be.wegenenverkeer.atomium.client.spring.SpringFeedHttpClient;
import be.wegenenverkeer.atomium.client.springboot.AtomiumFeedProperties.InitialFeedPointer;
import org.jspecify.annotations.Nullable;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Builds a ready-to-start {@link Feed} per {@link FeedHandler} in a fixed four-step sequence:
 * <ol>
 *   <li><b>defaults</b> — the {@link FeedConfiguration} is assembled from the narrow seam
 *       ({@link FeedRestClientBuilders}) plus the framework defaults: content mapper (app {@link JsonMapper}),
 *       executor (per-feed thread via {@link PerFeedThreadExecutors}) and the properties-driven exponential backoff;</li>
 *   <li><b>customize</b> — all {@link FeedCustomizer} beans (in {@code @Order} order) mutate it;</li>
 *   <li><b>validate</b> — fail fast at startup if a customizer set a required part to {@code null};</li>
 *   <li><b>assemble</b> — from the {@link FeedConfiguration} a {@link Feed} is built (with among others the
 *       {@link AtomiumClient} and the content decoder), and {@link FeedRuntime#of} assembles the
 *       running machinery from it.</li>
 * </ol>
 */
public class FeedFactory {

    private final FeedRestClientBuilders restClientBuilders;
    private final JsonMapper defaultContentMapper;
    private final PerFeedThreadExecutors perFeedThreadExecutors;
    private final List<FeedCustomizer> customizers;
    private final List<FeedEventListener> appWideListeners;
    private final AtomiumProperties atomiumProperties;
    private final FeedPointerRepository feedPointerRepository;
    private final FeedTransactions transactions;

    // The envelope decoder is framework-owned and shared; it uses its own JsonMapper (independent of app config).
    private final FeedPageDecoder feedPageDecoder = new JacksonFeedPageDecoder();
    // time source for the backoff timing in the runner; systemDefaultZone so that nextAttempt carries the local offset
    // (e.g. +02:00) instead of UTC. tests construct a runner with their own (fake) clock
    private final Clock clock = Clock.systemDefaultZone();

    public FeedFactory(FeedRestClientBuilders restClientBuilders, JsonMapper defaultContentMapper,
                       PerFeedThreadExecutors perFeedThreadExecutors, List<FeedCustomizer> customizers,
                       List<FeedEventListener> appWideListeners, AtomiumProperties atomiumProperties,
                       FeedPointerRepository feedPointerRepository, FeedTransactions transactions) {
        this.restClientBuilders = restClientBuilders;
        this.defaultContentMapper = defaultContentMapper;
        this.perFeedThreadExecutors = perFeedThreadExecutors;
        this.customizers = customizers;
        this.appWideListeners = appWideListeners;
        this.atomiumProperties = atomiumProperties;
        this.feedPointerRepository = feedPointerRepository;
        this.transactions = transactions;
    }

    public <T> FeedRuntime create(FeedHandler<T> handler) {
        String feedId = handler.getFeedId();
        AtomiumFeedProperties properties = readConfig(feedId, handler);
        FeedConfiguration configuration = buildConfiguration(feedId, properties, handler);
        return FeedRuntime.of(buildFeed(handler, configuration), clock);
    }

    /** Steps 1-3: defaults → customize → validate. Package-private so the orchestration can be tested on its own. */
    FeedConfiguration buildConfiguration(String feedId, AtomiumFeedProperties properties, FeedHandler<?> handler) {
        FeedConfiguration configuration = initWithDefaults(feedId, properties); // 1: seam + framework defaults
        appWideListeners.forEach(configuration::addListener);                // app-wide listener beans on every feed
        customizers.forEach(customizer -> customizer.customize(configuration)); // 2: customize (may add listeners per feed)
        configuration.validate();                                             // 3: fail fast
        return configuration;
    }

    /**
     * Step 1: assemble a fully populated {@link FeedConfiguration} from the narrow seam plus the framework defaults.
     * Only the {@link RestClient.Builder} comes from the application ({@link FeedRestClientBuilders}); content mapper,
     * executor and backoff are framework defaults that a {@link FeedCustomizer} can still replace per feed.
     */
    private FeedConfiguration initWithDefaults(String feedId, AtomiumFeedProperties properties) {
        RestClient.Builder restClientBuilder = restClientBuilders.restClientBuilderFor(feedId, properties);
        Executor executor = perFeedThreadExecutors.executorFor(feedId);
        AtomiumFeedProperties.Backoff backoff = properties.backoff();
        FeedBackoffPolicy backoffPolicy = new ExponentialFeedBackoffPolicy(
                backoff.initialInterval(), backoff.maxInterval(), backoff.multiplier());
        return new FeedConfiguration(feedId, properties, restClientBuilder, defaultContentMapper, executor, backoffPolicy);
    }

    /** Step 4a: build the {@link Feed} definition from the (validated) {@link FeedConfiguration}. */
    private <T> Feed<T> buildFeed(FeedHandler<T> handler, FeedConfiguration configuration) {
        AtomiumFeedProperties properties = configuration.properties();
        RestClient restClient = configuration.restClientBuilder().build();
        FeedHttpClient feedHttpClient = new SpringFeedHttpClient(restClient, properties.queryParams());
        AtomiumClient atomiumClient = new AtomiumClient(feedHttpClient, feedPageDecoder);

        FeedContentDecoder<T> decoder = JacksonFeedContentDecoder.of(handler, configuration.getContentMapper());

        Supplier<FeedPointer> initialFeedPointer = determineInitialFeedPointer(
                configuration.feedId(), properties.initialFeedPointer(), atomiumClient, feedPointerRepository);
        Feed.Builder<T> builder = Feed.builder(configuration.feedId(), handler, atomiumClient, decoder)
                .pointerRepository(feedPointerRepository)
                .transactions(transactions)
                .initialFeedPointer(initialFeedPointer)
                .backoffPolicy(configuration.getBackoffPolicy())
                .executor(configuration.getExecutor())
                .addListeners(configuration.listeners())
                .queryInterval(properties.queryInterval())
                .activeOnStartup(properties.activeOnStartup())
                .maxProcessingSize(properties.processing().maxSize());
        // only pass on explicitly set config; empty = the core default of the Feed builder
        Integer maxUncommittedPages = properties.processing().maxUncommittedPages();
        if (maxUncommittedPages != null) {
            builder.maxUncommittedPages(maxUncommittedPages);
        }
        return builder.build();
    }

    /**
     * Translates the {@code initialFeedPointer} config into a strategy that yields the start position when no
     * pointer has been persisted yet. The strategy is consulted <em>lazily</em> (on the feed thread, at the
     * first run), so that an HTTP call to the source feed ({@code oldest}/{@code now}) does not block
     * startup.
     *
     * <p><b>Fail fast:</b> if the config is missing and there is no persisted pointer yet, this is a
     * misconfiguration for a brand-new feed → an exception right away (at startup), instead of a feed that
     * silently never advances. That check is only a DB read (local dependency), no remote call.
     */
    static Supplier<FeedPointer> determineInitialFeedPointer(String feedId, @Nullable InitialFeedPointer config,
                                                           AtomiumClient atomiumClient,
                                                           FeedPointerRepository feedPointerRepository) {
        if (config == null) {
            // configuration validation, with property names in the message; core additionally asserts the same
            // condition framework-neutrally — deliberately two checks, each with a different purpose
            if (feedPointerRepository.find(feedId).isEmpty()) {
                throw new IllegalStateException(("feed '%s' has no persisted pointer and no "
                        + "'initialFeedPointer' config; configure initialFeedPointer "
                        + "(type: oldest | now | pointer + page-link)").formatted(feedId));
            }
            // There is a pointer in the repo → this supplier is never consulted; fails with a clear message if it is consulted anyway.
            return () -> {
                throw new IllegalStateException("feed '%s' has no 'initialFeedPointer' config".formatted(feedId));
            };
        }
        return switch (config.type()) {
            case OLDEST -> atomiumClient::pointerToOldest;
            case NOW -> atomiumClient::pointerFromNow;
            case POINTER -> {
                // the record validation of InitialFeedPointer guarantees that page-link is set with type 'pointer'
                String pageLink = Objects.requireNonNull(config.pageLink());
                yield () -> new FeedPointer(pageLink);
            }
        };
    }

    private AtomiumFeedProperties readConfig(String feedId, FeedHandler<?> handler) {
        AtomiumFeedProperties properties = atomiumProperties.feeds().get(feedId);
        if (properties == null) {
            throw new IllegalStateException(
                    "no atomium feed config found under 'atomium.feeds.%s' for handler %s"
                            .formatted(feedId, handler.getClass().getName()));
        }
        if (properties.url() == null || properties.url().isBlank()) {
            throw new IllegalStateException("atomium.feeds.%s.url is required".formatted(feedId));
        }
        validateProcessingConfig(feedId, handler, properties.processing());
        return properties;
    }

    /**
     * The whole {@code processing.*} group is only meaningful with a {@link SimpleProcessingFeedHandler}: any
     * other handler commits per entry, so the threshold is always 1 and the safety net can never fire — a
     * configured value would be a silent no-op. We reject fail-fast at startup instead.
     */
    static void validateProcessingConfig(String feedId, FeedHandler<?> handler,
                                         AtomiumFeedProperties.Processing processing) {
        // configuration validation, with the property names in the message; core additionally asserts the same
        // condition framework-neutrally — deliberately two checks, each with a different purpose
        if (handler instanceof SimpleProcessingFeedHandler<?, ?>) {
            return;
        }
        if (processing.maxSize() != null || processing.maxUncommittedPages() != null) {
            throw new IllegalStateException(("'atomium.feeds.%s.processing.*' is set, but handler %s is not a "
                    + "SimpleProcessingFeedHandler (it commits per entry, so a processing size and the safety "
                    + "net are meaningless). Remove the properties, or implement SimpleProcessingFeedHandler.")
                    .formatted(feedId, handler.getClass().getName()));
        }
    }
}
