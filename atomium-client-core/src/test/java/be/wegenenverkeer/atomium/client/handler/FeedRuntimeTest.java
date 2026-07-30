package be.wegenenverkeer.atomium.client.handler;

import be.wegenenverkeer.atomium.client.protocol.AtomiumEntry;
import be.wegenenverkeer.atomium.client.protocol.FeedPageMetadata;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * The batch threshold is resolved per handler type (see {@link FeedRuntime#controllerFor}).
 */
class FeedRuntimeTest {

    /**
     * A {@code preferredBatchSize} on a feed with an {@link EntryFeedHandler} is a configuration mistake — it processes
     * per entry, so the threshold is always 1. We reject it fail-fast during assembly instead of silently ignoring it.
     */
    @Test
    void aPreferredBatchSizeOnAnEntryFeedHandlerFailsFast() {
        assertThatIllegalStateException()
                .isThrownBy(() -> FeedRuntime.controllerFor("feed", handler("feed"), 50, 10))
                .withMessageContaining("preferredBatchSize")
                .withMessageContaining("EntryFeedHandler");
    }

    /** A {@link BatchedFeedHandler} without an explicit threshold gets the framework default (100). */
    @Test
    void aBatchedFeedHandlerWithoutConfigGetsTheDefaultThreshold() {
        assertThatNoException().isThrownBy(() -> FeedRuntime.controllerFor(
                "feed", batchHandler("feed"), null, 10));
    }

    /** A {@link FeedHandler} without either of the two variants has no entry callback → fail-fast. */
    @Test
    void aHandlerWithoutVariantFailsFast() {
        FeedHandler<String> bare = () -> "feed";

        assertThatIllegalStateException()
                .isThrownBy(() -> FeedRuntime.controllerFor("feed", bare, null, 10))
                .withMessageContaining("entry callback");
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
