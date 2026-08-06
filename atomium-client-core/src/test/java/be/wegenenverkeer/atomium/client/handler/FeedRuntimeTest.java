package be.wegenenverkeer.atomium.client.handler;

import be.wegenenverkeer.atomium.client.protocol.AtomiumEntry;
import be.wegenenverkeer.atomium.client.protocol.FeedPageMetadata;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * The processing threshold is resolved per handler type (see {@link FeedRuntime#processorFor}).
 */
class FeedRuntimeTest {

    /**
     * A {@code maxProcessingSize} on a feed with an {@link EntryFeedHandler} is a configuration mistake — it
     * processes per entry, so the threshold is always 1. We reject it fail-fast during assembly instead of silently
     * ignoring it.
     */
    @Test
    void aMaxProcessingSizeOnAnEntryFeedHandlerFailsFast() {
        assertThatIllegalStateException()
                .isThrownBy(() -> FeedRuntime.processorFor("feed", handler("feed"), 50))
                .withMessageContaining("maxProcessingSize")
                .withMessageContaining("EntryFeedHandler");
    }

    /** A {@link SimpleProcessingFeedHandler} without an explicit threshold gets the framework default (100). */
    @Test
    void aProcessingHandlerWithoutConfigGetsTheDefaultThreshold() {
        assertThatNoException().isThrownBy(() -> FeedRuntime.processorFor(
                "feed", batchHandler("feed"), null));
    }

    /** A handler with both variants is ambiguous: the framework refuses to silently pick one. */
    @Test
    void aHandlerWithBothVariantsFailsFast() {
        // both variants declare a default accepts; the forced override is what makes this compilable at all
        class BothVariantsHandler implements EntryFeedHandler<String>, SimpleProcessingFeedHandler<String, Integer> {
            @Override
            public String getFeedId() {
                return "feed";
            }

            @Override
            public boolean accepts(FeedPageMetadata pageMetadata, AtomiumEntry entry, String content) {
                return true;
            }

            @Override
            public void onEntry(FeedPageMetadata pageMetadata, AtomiumEntry entry, String content) {
            }

            @Override
            public ProcessResult<Integer> process(List<ProcessingEntry<String>> entries) {
                return ProcessResult.of(entries.size());
            }

            @Override
            public void persist(Integer prepared) {
            }
        }

        assertThatIllegalStateException()
                .isThrownBy(() -> FeedRuntime.processorFor("feed", new BothVariantsHandler(), null))
                .withMessageContaining("both")
                .withMessageContaining("exactly one variant");
    }

    /** A {@link FeedHandler} without either of the two variants has no entry callback → fail-fast. */
    @Test
    void aHandlerWithoutVariantFailsFast() {
        FeedHandler<String> bare = () -> "feed";

        assertThatIllegalStateException()
                .isThrownBy(() -> FeedRuntime.processorFor("feed", bare, null))
                .withMessageContaining("entry callback");
    }

    private static SimpleProcessingFeedHandler<String, Integer> batchHandler(String feedId) {
        return new SimpleProcessingFeedHandler<>() {
            @Override
            public String getFeedId() {
                return feedId;
            }

            @Override
            public ProcessResult<Integer> process(List<ProcessingEntry<String>> entries) {
                return ProcessResult.of(entries.size());
            }

            @Override
            public void persist(Integer prepared) {
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
