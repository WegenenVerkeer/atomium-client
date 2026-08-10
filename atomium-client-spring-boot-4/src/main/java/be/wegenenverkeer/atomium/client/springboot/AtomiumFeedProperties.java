package be.wegenenverkeer.atomium.client.springboot;

import be.wegenenverkeer.atomium.client.handler.SimpleProcessingFeedHandler;
import be.wegenenverkeer.atomium.client.handler.ExponentialFeedBackoffPolicy;
import be.wegenenverkeer.atomium.client.handler.FeedDefaults;
import be.wegenenverkeer.atomium.client.handler.FeedHandler;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The generic config for one feed, bound as the value in {@code atomium.feeds.<feedId>} (the map key is the
 * {@link FeedHandler#getFeedId() feedId}, e.g. {@code atomium.feeds.a-feed-id}).
 *
 * <p>The {@code url} (the feed base url) is deliberately generic here: every application needs it. The <em>other</em>
 * HTTP client config (auth, timeouts, retries, …) is environment-specific and is bound from the config by the
 * HTTP client seam ({@link FeedRestClientBuilders}) itself (e.g. its own {@code atomium.feeds.<feedId>.<block>}
 * to its own type). Unknown sub-properties are ignored during binding, so that extra config does not break the binding of this
 * record.
 *
 * @param url                 the feed base url; required (the framework fails fast at startup when it is missing)
 * @param queryParams         optional: query parameters sent with <em>every</em> fetch (the head as well as every
 *                            page), added to any query parameters the page href itself carries — for feed
 *                            servers whose behavior is steered per request, e.g. a server-side filter:
 *                            {@code query-params: {ignore-readmodels: true, type: [x, y]}} (a scalar binds as a
 *                            single-element list); empty by default
 * @param activeOnStartup    whether the consumer may start automatically
 * @param queryInterval       the poll frequency: the wait time between the end of a successful run and the next
 * @param initialFeedPointer optional: the start position for a brand-new feed (only used as long as no
 *                            pointer has been persisted yet). When absent, an already persisted pointer is
 *                            expected (otherwise the feed fails at startup).
 * @param backoff             the backoff on consecutive failed runs (default: exponential
 *                            {@value Defaults#BACKOFF_INITIAL_INTERVAL} → … → {@value Defaults#BACKOFF_MAX_INTERVAL})
 * @param processing          the processing tuning; only meaningful with a {@link SimpleProcessingFeedHandler}
 */
public record AtomiumFeedProperties(
        @Nullable String url,
        @DefaultValue Map<String, List<String>> queryParams,
        @DefaultValue(Defaults.ACTIVE_ON_STARTUP) boolean activeOnStartup,
        @DefaultValue(Defaults.QUERY_INTERVAL) Duration queryInterval,
        @Nullable InitialFeedPointer initialFeedPointer,
        @DefaultValue Backoff backoff,
        @DefaultValue Processing processing
) {

    public AtomiumFeedProperties {
        // deep, immutable copy that keeps the binding order (deterministic request URIs)
        Map<String, List<String>> queryParamsCopy = new LinkedHashMap<>();
        queryParams.forEach((name, values) -> queryParamsCopy.put(name, List.copyOf(values)));
        queryParams = Collections.unmodifiableMap(queryParamsCopy);
        if (queryInterval.isZero() || queryInterval.isNegative()) {
            throw new IllegalArgumentException("query-interval must be positive, was " + queryInterval);
        }
    }

    /**
     * The binding defaults of this config, in one findable place (the {@code @DefaultValue} annotations use
     * these constants; the javadoc refers to them with {@code {@value}}). The processing defaults are deliberately
     * <em>not</em> here: they are behavior of the core layer — see {@link FeedDefaults}.
     */
    public static final class Defaults {

        public static final String ACTIVE_ON_STARTUP = "false";
        public static final String QUERY_INTERVAL = "1m";
        public static final String BACKOFF_INITIAL_INTERVAL = "1m";
        public static final String BACKOFF_MAX_INTERVAL = "1h";
        public static final String BACKOFF_MULTIPLIER = "2";

        private Defaults() {
        }
    }

    /**
     * The processing tuning ({@code atomium.feeds.<feedId>.processing.*}). Deliberately config rather than code:
     * what the processing <em>does</em> is a domain concern (the developer writes {@code process}/{@code persist}),
     * but the <em>size</em> is a tuning parameter that differs per environment.
     *
     * <p>Both parameters are optional; empty = the lib's default (the {@code Feed} layer in core knows the
     * defaults, this config only passes on what is set explicitly). The whole group is only meaningful with a
     * {@link SimpleProcessingFeedHandler}; on any other handler (which commits per entry, so the threshold is
     * always 1 and the safety net can never fire) a set value deliberately fails at startup instead of being
     * silently ignored.
     *
     * @param maxSize     the <em>maximum</em> batch size, counted in accepted entries (a batch is processed
     *                    at this size, or smaller when the safety net, the end of the feed, an interruption
     *                    or a read failure wraps it up first);
     *                          empty → {@value FeedDefaults#MAX_PROCESSING_SIZE}.
     * @param maxUncommittedPages the <b>safety net</b>: once this many pages have been read without a commit, every
     *                          boundary asks the processing to wrap up (even a partial batch);
     *                          empty → {@value FeedDefaults#MAX_UNCOMMITTED_PAGES}.
     *                          Without this, a heavily filtering feed would rarely reach its threshold:
     *                          the feed pointer then stays pinned, there is no intermediate progress, and a crash has
     *                          to re-fetch an unbounded number of pages.
     */
    public record Processing(
            @Nullable Integer maxSize,
            @Nullable Integer maxUncommittedPages
    ) {

        public Processing {
            if (maxSize != null && maxSize < 1) {
                throw new IllegalArgumentException(
                        "processing.max-size must be at least 1, was " + maxSize);
            }
            if (maxUncommittedPages != null && maxUncommittedPages < 1) {
                throw new IllegalArgumentException(
                        "processing.max-uncommitted-pages must be at least 1, was " + maxUncommittedPages);
            }
        }
    }

    /**
     * The exponential backoff on consecutive failed runs (see {@link ExponentialFeedBackoffPolicy}).
     *
     * @param initialInterval the wait time after the first failure; must be positive
     * @param maxInterval      the upper bound on the wait time; at least {@code initial-interval}
     * @param multiplier       the factor by which the wait time grows per failure; at least 1
     */
    public record Backoff(
            @DefaultValue(Defaults.BACKOFF_INITIAL_INTERVAL) Duration initialInterval,
            @DefaultValue(Defaults.BACKOFF_MAX_INTERVAL) Duration maxInterval,
            @DefaultValue(Defaults.BACKOFF_MULTIPLIER) double multiplier
    ) {

        public Backoff {
            if (initialInterval.isZero() || initialInterval.isNegative()) {
                throw new IllegalArgumentException(
                        "backoff.initial-interval must be positive, was " + initialInterval);
            }
            if (maxInterval.compareTo(initialInterval) < 0) {
                throw new IllegalArgumentException(
                        "backoff.max-interval (%s) must not be smaller than backoff.initial-interval (%s)"
                                .formatted(maxInterval, initialInterval));
            }
            if (multiplier < 1) {
                throw new IllegalArgumentException("backoff.multiplier must be at least 1, was " + multiplier);
            }
        }
    }

    /**
     * The start position of a brand-new feed.
     *
     * @param type     the strategy: from the oldest page, from now, or an explicit page link; required
     * @param pageLink only for {@link Type#POINTER} (and then required): the page href relative to the base url
     *                 (e.g. {@code /182})
     */
    public record InitialFeedPointer(Type type, @Nullable String pageLink) {

        @SuppressWarnings("ConstantValue") // the Boot binder CAN bind null for a missing 'type'
        public InitialFeedPointer {
            if (type == null) {
                throw new IllegalArgumentException(
                        "initial-feed-pointer.type is required (oldest | now | pointer)");
            }
            if (type == Type.POINTER && (pageLink == null || pageLink.isBlank())) {
                throw new IllegalArgumentException(
                        "initial-feed-pointer type 'pointer' requires a 'page-link'");
            }
            if (type != Type.POINTER && pageLink != null) {
                throw new IllegalArgumentException(
                        ("initial-feed-pointer.page-link ('%s') is only meaningful with type 'pointer', not with '%s' "
                                + "— remove the page-link or use type 'pointer'").formatted(pageLink, type));
            }
        }

        public enum Type {
            /** From the oldest page: consume the full history. */
            OLDEST,
            /** Only events added after initialization (skip the existing ones on the head). */
            NOW,
            /** From an explicit page link ({@code page-link}). */
            POINTER
        }
    }
}
