package be.wegenenverkeer.atomium.client.springboot;

import be.wegenenverkeer.atomium.client.handler.Feeds;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * In-context integration test for the {@link FeedScheduler}: as soon as a feed is active, the scheduler must
 * poll and consume it <em>on its own</em> at its {@code query-interval} — without the test calling
 * {@code tryToStart()} anywhere. Runs on the real per-feed thread (no inline executor), so the
 * scheduler tick is non-blocking and the run happens asynchronously.
 *
 * <p>We deliberately let the feed start inactive and only activate it after the WireMock feed is fully stubbed
 * (as an admin endpoint or {@code active-on-startup} would do on a fully started server); otherwise an early
 * tick would run into a half-set-up feed.
 */
@TestPropertySource(properties = "atomium.feeds.foo-app.query-interval=200ms")
class FeedSchedulerIT extends AbstractAtomiumFeedIT {

    @Autowired
    private FooAppFeedHandler handler;

    @Autowired
    private Feeds feeds;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void reset() {
        wiremock.resetAll();
        handler.reset();
        jdbcClient.sql("TRUNCATE atomium_feed_pointer_v1").update();
    }

    @AfterEach
    void stopFeed() {
        // otherwise the scheduler keeps polling on the (cached) context
        feeds.get(handler.getFeedId()).runner().deactivate();
    }

    @Test
    void theSchedulerConsumesTheFeedOnItsOwn() {
        wiremock.stubFor(get(urlPathEqualTo("/feed")).willReturn(okJson(resource("2-v1.json"))));   // head
        wiremock.stubFor(get(urlPathEqualTo("/feed/0")).willReturn(okJson(resource("0.json"))));
        wiremock.stubFor(get(urlPathEqualTo("/feed/1")).willReturn(okJson(resource("1.json"))));
        wiremock.stubFor(get(urlPathEqualTo("/feed/2")).willReturn(okJson(resource("2-v1.json"))));

        // only now activate the feed; the scheduler picks it up on the next tick (no manual tryToStart)
        feeds.get(handler.getFeedId()).runner().activate();

        // we wait until the scheduler has consumed the whole feed: id-008 is the last event of the feed,
        // so waiting for it guarantees all preceding entries are already there
        awaitUntil(() -> handler.invocations().contains("onEntry(/2, id-008, fieldValue-8)"));

        // all events were delivered in read order (repeated ticks do not break the subsequence)
        assertThat(handler.invocations()).containsSubsequence(
                "onEntry(/0, id-001, fieldValue-1)",
                "onEntry(/0, id-002, fieldValue-2)",
                "onEntry(/0, id-003, fieldValue-3)",
                "onEntry(/1, id-004, fieldValue-4)",
                "onEntry(/1, id-005, fieldValue-5)",
                "onEntry(/1, id-006, fieldValue-6)",
                "onEntry(/2, id-007, fieldValue-7)",
                "onEntry(/2, id-008, fieldValue-8)");
    }

    private static void awaitUntil(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("the scheduler did not consume the feed within the timeout");
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
