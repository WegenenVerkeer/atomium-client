package be.wegenenverkeer.atomium.client.core.demo;

import be.wegenenverkeer.atomium.client.handler.Feeds;
import be.wegenenverkeer.atomium.client.handler.SimpleFeedScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test: the context loads (all feed assemblies + registry + scheduler + H2 schema) and the three feeds
 * are registered. In the test all feeds are inactive ({@code application-test.yml} + defaults), so the
 * scheduler ticks poll nothing.
 */
@SpringBootTest
@ActiveProfiles("test")
class CoreDemoApplicationContextTest {

    @Autowired
    private Feeds feeds;

    @Autowired
    private SimpleFeedScheduler scheduler;

    @Test
    void contextLoadsAndAllFeedsAreAssembled() {
        assertThat(feeds.all()).extracting(feed -> feed.feedId())
                .containsExactlyInAnyOrder("full-monty", "simple", "simple-processing");
        assertThat(feeds.get("simple").runner().isActive()).isFalse();      // test profile: inactive
        assertThat(feeds.get("full-monty").runner().isActive()).isFalse();
        assertThat(feeds.get("simple-processing").runner().isActive()).isFalse();
        assertThat(scheduler).isNotNull();
    }
}
