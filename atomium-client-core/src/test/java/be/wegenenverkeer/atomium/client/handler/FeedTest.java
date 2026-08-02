package be.wegenenverkeer.atomium.client.handler;

import be.wegenenverkeer.atomium.client.fetch.AtomiumClient;
import be.wegenenverkeer.atomium.client.fetch.FakeFeedHttpClient;
import be.wegenenverkeer.atomium.client.fetch.JacksonFeedPageDecoder;
import be.wegenenverkeer.atomium.client.protocol.AtomiumEntry;
import be.wegenenverkeer.atomium.client.protocol.FeedPageMetadata;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * The {@link Feed.Builder} validates fail-fast: a definition that cannot run may never become a {@link Feed}
 * (the most dangerous case — {@code maxUncommittedPages < 1} — would otherwise silently degrade to a flush on
 * every page boundary).
 */
class FeedTest {

    private static final FeedContentDecoder<String> DECODER = value -> value;

    @Test
    void aBlankFeedIdFails() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Feed.builder("  ", handler(), client(), DECODER))
                .withMessageContaining("feedId");
    }

    @Test
    void aMissingBuildingBlockFails() {
        assertThatNullPointerException()
                .isThrownBy(() -> Feed.builder("feed", null, client(), DECODER))
                .withMessageContaining("handler");
        assertThatNullPointerException()
                .isThrownBy(() -> Feed.builder("feed", handler(), null, DECODER))
                .withMessageContaining("atomiumClient");
        assertThatNullPointerException()
                .isThrownBy(() -> Feed.builder("feed", handler(), client(), null))
                .withMessageContaining("contentDecoder");
        assertThatNullPointerException()
                .isThrownBy(() -> builder().pointerRepository(null))
                .withMessageContaining("pointerRepository");
        assertThatNullPointerException()
                .isThrownBy(() -> builder().transactions(null))
                .withMessageContaining("transactions");
    }

    @Test
    void theQueryIntervalMustBePositive() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> builder().queryInterval(Duration.ZERO))
                .withMessageContaining("queryInterval");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> builder().queryInterval(Duration.ofSeconds(-1)))
                .withMessageContaining("queryInterval");
    }

    @Test
    void maxUncommittedPagesMustBeAtLeastOne() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> builder().maxUncommittedPages(0))
                .withMessageContaining("maxUncommittedPages");
    }

    @Test
    void maxProcessingSizeMustBeAtLeastOneOrAbsent() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> builder().maxProcessingSize(0))
                .withMessageContaining("maxProcessingSize");
        builder().maxProcessingSize(null);   // absent is valid (framework default)
    }

    private static Feed.Builder<String> builder() {
        return Feed.builder("feed", handler(), client(), DECODER);
    }

    private static AtomiumClient client() {
        return new AtomiumClient(new FakeFeedHttpClient(), new JacksonFeedPageDecoder());
    }

    private static EntryFeedHandler<String> handler() {
        return new EntryFeedHandler<>() {
            @Override
            public String getFeedId() {
                return "feed";
            }

            @Override
            public void onEntry(FeedPageMetadata pageMetadata, AtomiumEntry entry, String content) {
            }
        };
    }
}
