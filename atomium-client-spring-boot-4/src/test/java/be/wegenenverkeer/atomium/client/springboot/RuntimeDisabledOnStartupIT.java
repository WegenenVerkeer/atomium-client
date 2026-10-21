package be.wegenenverkeer.atomium.client.springboot;

import be.wegenenverkeer.atomium.client.handler.FeedRunner;
import be.wegenenverkeer.atomium.client.handler.Feeds;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code atomium.runtime.enabled-on-startup=false} — a standby instance: the feeds keep their wanted state
 * ({@code active-on-startup}), but nothing runs and nothing is seeded until {@code Feeds.enable()}. The gauge
 * {@code atomium.feed.last.success.time} is the thing to watch: seeded on {@code feedActivated}, it would make a
 * staleness alert fire on a standby that never commits.
 */
@TestPropertySource(properties = {
        "atomium.runtime.enabled-on-startup=false",
        "atomium.feeds.foo-app.active-on-startup=true"
})
@Import(RuntimeDisabledOnStartupIT.MetricsConfig.class)
class RuntimeDisabledOnStartupIT extends AbstractAtomiumFeedIT {

    private static final String FEED = "foo-app";

    /** A registry, so that the metrics listener is wired and the gauge can be observed. */
    @TestConfiguration
    static class MetricsConfig {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Autowired
    private Feeds feeds;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void theFeedIsWantedButNotConsumedAndNoGaugeIsSeededUntilEnabled() {
        FeedRunner runner = feeds.get(FEED).runner();
        assertThat(feeds.isEnabled()).isFalse();
        assertThat(runner.isActive()).isTrue();
        assertThat(runner.isEnabled()).isFalse();
        assertThat(runner.tryToStart()).isFalse();
        assertThat(meterRegistry.find("atomium.feed.last.success.time").tag("feed", FEED).timeGauge()).isNull();

        feeds.enable();
        try {
            assertThat(runner.isEnabled()).isTrue();
            assertThat(meterRegistry.find("atomium.feed.last.success.time").tag("feed", FEED).timeGauge()).isNotNull();
        } finally {
            feeds.disable();
        }
    }
}
