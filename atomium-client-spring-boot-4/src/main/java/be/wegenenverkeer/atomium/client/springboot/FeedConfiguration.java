package be.wegenenverkeer.atomium.client.springboot;

import be.wegenenverkeer.atomium.client.handler.Feed;
import be.wegenenverkeer.atomium.client.handler.FeedBackoffPolicy;
import be.wegenenverkeer.atomium.client.handler.FeedEventListener;
import be.wegenenverkeer.atomium.client.handler.FeedRunner;

import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * The complete configuration of one feed as the framework is going to build it. It is fully populated with
 * defaults by {@link FeedFactory} (the {@link RestClient.Builder} from the narrow seam
 * {@link FeedRestClientBuilders}, the rest from framework defaults), after which the {@link FeedCustomizer} beans
 * mutate it (in {@code @Order} order), and finally it is validated ({@link #validate()}) and assembled into a
 * {@link Feed}.
 *
 * <p>Every variation point offers three styles, precisely because the defaults are already populated:
 * <ul>
 *   <li><b>replace</b> — {@code feed.setContentMapper(ownMapper)};</li>
 *   <li><b>decorate</b> — {@code feed.setContentMapper(wrap(feed.getContentMapper()))};</li>
 *   <li><b>extend</b> — {@code feed.restClientBuilder().requestInterceptor(extra)} (the builder already carries
 *       logging/retries/auth, so one extra interceptor requires no rebuild).</li>
 * </ul>
 *
 * <p>The {@link #properties()} are read-only: per-feed properties overrides belong in the config
 * ({@code atomium.feeds.<feedId>}), not in a customizer. Later iterations add fields here (error
 * handler) — each time a field + getter/setter, binary compatible.
 */
public class FeedConfiguration {

    private final String feedId;
    private final AtomiumFeedProperties properties;

    private RestClient.Builder restClientBuilder;
    private JsonMapper contentMapper;
    private Executor executor;
    private FeedBackoffPolicy backoffPolicy;
    private final List<FeedEventListener> listeners = new ArrayList<>();

    public FeedConfiguration(String feedId, AtomiumFeedProperties properties, RestClient.Builder restClientBuilder,
                            JsonMapper contentMapper, Executor executor, FeedBackoffPolicy backoffPolicy) {
        this.feedId = feedId;
        this.properties = properties;
        this.restClientBuilder = restClientBuilder;
        this.contentMapper = contentMapper;
        this.executor = executor;
        this.backoffPolicy = backoffPolicy;
    }

    public String feedId() {
        return feedId;
    }

    /** The bound config of this feed (read-only: change the {@code atomium.feeds.<feedId>} properties, not this). */
    public AtomiumFeedProperties properties() {
        return properties;
    }

    /**
     * The builder of the HTTP client, as delivered by the {@link FeedRestClientBuilders} seam (base url and possibly
     * logging/retries/auth). Add something to it (e.g. an extra {@code requestInterceptor}) or replace it via
     * {@link #setRestClientBuilder}.
     */
    public RestClient.Builder restClientBuilder() {
        return restClientBuilder;
    }

    public void setRestClientBuilder(RestClient.Builder restClientBuilder) {
        this.restClientBuilder = restClientBuilder;
    }

    /** The {@link JsonMapper} used to deserialize the entry content (default: the app mapper). */
    public JsonMapper getContentMapper() {
        return contentMapper;
    }

    public void setContentMapper(JsonMapper contentMapper) {
        this.contentMapper = contentMapper;
    }

    /**
     * The {@link Executor} on which the {@link FeedRunner} runs this feed's runs (default: a dedicated
     * daemon thread per feed). Replace it e.g. with {@code Runnable::run} for a synchronous run, or with a
     * shared pool.
     */
    public Executor getExecutor() {
        return executor;
    }

    public void setExecutor(Executor executor) {
        this.executor = executor;
    }

    /** The {@link FeedBackoffPolicy} on consecutive failed runs (default: properties-driven exponential). */
    public FeedBackoffPolicy getBackoffPolicy() {
        return backoffPolicy;
    }

    public void setBackoffPolicy(FeedBackoffPolicy backoffPolicy) {
        this.backoffPolicy = backoffPolicy;
    }

    /**
     * The {@link FeedEventListener}s of this feed (additive): app-wide listener beans are already in it; add
     * per-feed ones with {@link #addListener}. Metrics/health/alerting are all consumers of these events.
     */
    public List<FeedEventListener> listeners() {
        return listeners;
    }

    /** Add a {@link FeedEventListener} for this feed (in addition to the app-wide beans). */
    public void addListener(FeedEventListener listener) {
        listeners.add(listener);
    }

    /**
     * Fail fast after the customize phase: a {@link FeedCustomizer} must not set a required part to {@code null}.
     * Called at startup, so that a failure is visible immediately instead of at the first run.
     *
     * @throws IllegalStateException if a required part is missing
     */
    void validate() {
        if (restClientBuilder == null) {
            throw new IllegalStateException(
                    "feed '%s': restClientBuilder is null (did a FeedCustomizer set it to null?)".formatted(feedId));
        }
        if (contentMapper == null) {
            throw new IllegalStateException(
                    "feed '%s': contentMapper is null (did a FeedCustomizer set it to null?)".formatted(feedId));
        }
        if (executor == null) {
            throw new IllegalStateException(
                    "feed '%s': executor is null (did a FeedCustomizer set it to null?)".formatted(feedId));
        }
        if (backoffPolicy == null) {
            throw new IllegalStateException(
                    "feed '%s': backoffPolicy is null (did a FeedCustomizer set it to null?)".formatted(feedId));
        }
    }
}
