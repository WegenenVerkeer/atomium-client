package be.wegenenverkeer.atomium.client.handler;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * The {@link Feeds} registry fails fast on a duplicate feedId (a classic copy-paste mistake in
 * {@code getFeedId()}) and, on an unknown feedId, names the feeds that <em>are</em> registered.
 */
class FeedsTest {

    @Test
    void aDuplicateFeedIdFailsFast() {
        assertThatIllegalStateException()
                .isThrownBy(() -> new Feeds(List.of(feed("feed-a"), feed("feed-a"))))
                .withMessageContaining("duplicate feedId")
                .withMessageContaining("feed-a");
    }

    @Test
    void anUnknownFeedIdNamesTheKnownFeeds() {
        Feeds feeds = new Feeds(List.of(feed("feed-a"), feed("feed-b")));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> feeds.get("feed-typo"))
                .withMessageContaining("feed-typo")
                .withMessageContaining("feed-a")
                .withMessageContaining("feed-b");
    }

    private static FeedRuntime feed(String feedId) {
        FeedRunner runner = new FeedRunner(feedId, Duration.ofMinutes(1), isInterrupted -> {
        }, Runnable::run, false, n -> Duration.ofMinutes(1), Clock.systemUTC(), new FeedEventListener() {
        });
        return TestFeedRuntimes.withRunnerOnly(runner);
    }
}
