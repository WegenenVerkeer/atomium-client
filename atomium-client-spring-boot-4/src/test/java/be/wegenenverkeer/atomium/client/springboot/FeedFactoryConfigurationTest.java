package be.wegenenverkeer.atomium.client.springboot;

import be.wegenenverkeer.atomium.client.handler.BatchedFeedHandler;
import be.wegenenverkeer.atomium.client.handler.EntryFeedHandler;
import be.wegenenverkeer.atomium.client.handler.FeedEventListener;
import be.wegenenverkeer.atomium.client.handler.FeedHandler;
import be.wegenenverkeer.atomium.client.handler.FeedHandlerBatch;
import be.wegenenverkeer.atomium.client.handler.PerFeedThreadExecutors;

import be.wegenenverkeer.atomium.client.protocol.AtomiumEntry;
import be.wegenenverkeer.atomium.client.protocol.FeedPageMetadata;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Tests the sequence defaults → customize → validate in {@link FeedFactory#buildConfiguration}: without customizers
 * the defaults stay in place, customizers replace/augment (last one wins), {@link FeedCustomizer#forFeed}
 * filters on feedId, and a mandatory part set to {@code null} fails fast.
 */
class FeedFactoryConfigurationTest {

    private static final JsonMapper DEFAULT_MAPPER = JsonMapper.builder().build();

    // The narrow seam: just a fresh RestClient.Builder per feed. The factory supplies the content mapper, executor and backoff itself.
    private final FeedRestClientBuilders restClientBuilders = (feedId, properties) -> RestClient.builder();
    private final PerFeedThreadExecutors executors = new PerFeedThreadExecutors();

    @Test
    void withoutCustomizersTheDefaultsStayInPlace() {
        FeedConfiguration configuration = factory().buildConfiguration("feed", props(), handler("feed"));

        assertThat(configuration.getContentMapper()).isSameAs(DEFAULT_MAPPER);
        assertThat(configuration.restClientBuilder()).isNotNull();
    }

    @Test
    void aCustomizerReplacesTheMapper() {
        JsonMapper own = JsonMapper.builder().build();

        FeedConfiguration configuration = factory(feed -> feed.setContentMapper(own))
                .buildConfiguration("feed", props(), handler("feed"));

        assertThat(configuration.getContentMapper()).isSameAs(own);
    }

    @Test
    void withMultipleCustomizersTheLastOneWins() {
        JsonMapper first = JsonMapper.builder().build();
        JsonMapper last = JsonMapper.builder().build();

        FeedConfiguration configuration = factory(feed -> feed.setContentMapper(first), feed -> feed.setContentMapper(last))
                .buildConfiguration("feed", props(), handler("feed"));

        assertThat(configuration.getContentMapper()).isSameAs(last);
    }

    @Test
    void forFeedFiltersOnFeedId() {
        // the customizer targets a different feed → not applied to 'feed'
        FeedConfiguration configuration = factory(FeedCustomizer.forFeed("other-feed", feed -> feed.setContentMapper(null)))
                .buildConfiguration("feed", props(), handler("feed"));

        assertThat(configuration.getContentMapper()).isSameAs(DEFAULT_MAPPER);
    }

    @Test
    void aCustomizerThatSetsSomethingToNullFailsImmediately() {
        assertThatIllegalStateException().isThrownBy(() ->
                        factory(feed -> feed.setContentMapper(null)).buildConfiguration("feed", props(), handler("feed")))
                .withMessageContaining("contentMapper");
    }

    @Test
    void aCustomizerCanReplaceTheExecutor() {
        java.util.concurrent.Executor own = Runnable::run;

        FeedConfiguration configuration = factory(feed -> feed.setExecutor(own))
                .buildConfiguration("feed", props(), handler("feed"));

        assertThat(configuration.getExecutor()).isSameAs(own);
    }

    @Test
    void listenersAreAddedAppWideAndPerCustomizer() {
        FeedEventListener appWide = new FeedEventListener() {
        };
        FeedEventListener perFeed = new FeedEventListener() {
        };
        FeedFactory factory = new FeedFactory(restClientBuilders, DEFAULT_MAPPER, executors,
                List.of(feed -> feed.addListener(perFeed)), List.of(appWide), null, null, null);

        FeedConfiguration configuration = factory.buildConfiguration("feed", props(), handler("feed"));

        // app-wide listeners first, then what the customizer adds
        assertThat(configuration.listeners()).containsExactly(appWide, perFeed);
    }

    // Only the seam, mapper/executor defaults, 'customizers' and app-wide listeners play a role in buildConfiguration; the rest does not.
    private FeedFactory factory(FeedCustomizer... customizers) {
        return new FeedFactory(restClientBuilders, DEFAULT_MAPPER, executors, List.of(customizers), List.of(),
                null, null, null);
    }

    /**
     * The <em>configuration validation</em> (with the property name in the message): a {@code preferred-batch-size}
     * on a feed with an {@link EntryFeedHandler} is a configuration mistake — that handler processes per entry, so the threshold
     * is always 1. We reject the property fail-fast at startup instead of silently ignoring it. (Core additionally
     * asserts the same condition framework-neutrally; see {@code FeedRuntimeTest} in core.)
     */
    @Test
    void aPreferredBatchSizeOnAnEntryFeedHandlerFailsWithThePropertyName() {
        assertThatIllegalStateException()
                .isThrownBy(() -> FeedFactory.validateBatchConfig(
                        "feed", handler("feed"), new AtomiumFeedProperties.Batch(50, 10)))
                .withMessageContaining("atomium.feeds.feed.batch.preferred-batch-size")
                .withMessageContaining("EntryFeedHandler");
    }

    /** On a {@link BatchedFeedHandler} the property <em>is</em> meaningful. */
    @Test
    void aPreferredBatchSizeOnABatchedFeedHandlerIsValid() {
        assertThatNoException().isThrownBy(() -> FeedFactory.validateBatchConfig(
                "feed", batchHandler("feed"), new AtomiumFeedProperties.Batch(50, 10)));
    }

    private static BatchedFeedHandler<String> batchHandler(String feedId) {
        return new BatchedFeedHandler<>() {
            @Override
            public String getFeedId() {
                return feedId;
            }

            @Override
            public void onBatch(FeedHandlerBatch<String> batch) {
            }
        };
    }

    private static AtomiumFeedProperties props() {
        return new AtomiumFeedProperties("http://localhost/feed", false, Duration.ofMinutes(1), null,
                new AtomiumFeedProperties.Backoff(Duration.ofMinutes(1), Duration.ofHours(1), 2),
                new AtomiumFeedProperties.Batch(null, 10));
    }

    private static FeedHandler<String> handler(String feedId) {
        return new EntryFeedHandler<String>() {
            @Override
            public String getFeedId() {
                return feedId;
            }

            @Override
            public void onEntry(FeedPageMetadata pageMetadata, AtomiumEntry entry, String content) {
            }
        };
    }
}
