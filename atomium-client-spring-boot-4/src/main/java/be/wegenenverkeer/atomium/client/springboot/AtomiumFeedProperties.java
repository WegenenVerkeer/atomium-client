package be.wegenenverkeer.atomium.client.springboot;

import be.wegenenverkeer.atomium.client.handler.EntryFeedHandler;
import be.wegenenverkeer.atomium.client.handler.SimpleBatchedProcessingFeedHandler;
import be.wegenenverkeer.atomium.client.handler.ExponentialFeedBackoffPolicy;
import be.wegenenverkeer.atomium.client.handler.FeedDefaults;
import be.wegenenverkeer.atomium.client.handler.FeedHandler;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

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
 * @param activeOnStartup    whether the consumer may start automatically
 * @param queryInterval       the poll frequency: the wait time between the end of a successful run and the next
 * @param initialFeedPointer optional: the start position for a brand-new feed (only used as long as no
 *                            pointer has been persisted yet). When absent, an already persisted pointer is
 *                            expected (otherwise the feed fails at startup).
 * @param backoff             the backoff on consecutive failed runs (default: exponential
 *                            {@value Defaults#BACKOFF_INITIAL_INTERVAL} → … → {@value Defaults#BACKOFF_MAX_INTERVAL})
 * @param processing          the processing tuning; only meaningful with a {@link SimpleBatchedProcessingFeedHandler}
 */
public record AtomiumFeedProperties(
        @Nullable String url,
        @DefaultValue(Defaults.ACTIVE_ON_STARTUP) boolean activeOnStartup,
        @DefaultValue(Defaults.QUERY_INTERVAL) Duration queryInterval,
        @Nullable InitialFeedPointer initialFeedPointer,
        @DefaultValue Backoff backoff,
        @DefaultValue Processing processing
) {

    public AtomiumFeedProperties {
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
     * defaults, this config only passes on what is set explicitly).
     *
     * @param preferredSize     the number of accepted entries at which a batch is processed;
     *                          empty → {@value FeedDefaults#PREFERRED_PROCESSING_SIZE}.
     *                          Only meaningful with a {@link SimpleBatchedProcessingFeedHandler}: if an
     *                          {@link EntryFeedHandler} backs this feed, a value that is set deliberately fails at
     *                          startup (that handler processes per entry — a processing size would be silently ignored).
     * @param maxUncommittedPages the <b>safety net</b>: once this many pages have been read without a commit, every
     *                          boundary asks the processing to wrap up (even a partial batch);
     *                          empty → {@value FeedDefaults#MAX_UNCOMMITTED_PAGES}.
     *                          Without this, a heavily filtering feed would rarely reach its threshold:
     *                          the feed pointer then stays pinned, there is no intermediate progress, and a crash has
     *                          to re-fetch an unbounded number of pages. With an {@link EntryFeedHandler}
     *                          (commit per entry) this safety net never triggers.
     */
    public record Processing(
            @Nullable Integer preferredSize,
            @Nullable Integer maxUncommittedPages
    ) {

        public Processing {
            if (preferredSize != null && preferredSize < 1) {
                throw new IllegalArgumentException(
                        "processing.preferred-size must be at least 1, was " + preferredSize);
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
