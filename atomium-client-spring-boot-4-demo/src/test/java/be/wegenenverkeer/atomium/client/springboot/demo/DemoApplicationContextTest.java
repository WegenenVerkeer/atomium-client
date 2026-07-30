package be.wegenenverkeer.atomium.client.springboot.demo;

import be.wegenenverkeer.atomium.client.handler.Feeds;
import be.wegenenverkeer.atomium.client.springboot.MicrometerFeedEventListener;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test: loads the complete demo context (autoconfig + the in-memory security + the atomium wiring) against
 * a real postgres (testcontainers), and verifies that the {@code simple} and {@code full-monty} feed are
 * registered. That tells us the app boots with only the narrow seam ({@link DemoFeedRestClientBuilders}).
 */
@SpringBootTest
@ActiveProfiles("test")
class DemoApplicationContextTest {

    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    static {
        postgres.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private Feeds feeds;

    @Autowired
    private org.springframework.context.ApplicationContext context;

    @Test
    void contextLoadsAndRegistersAllFeeds() {
        assertThat(feeds.get("simple")).isNotNull();
        assertThat(feeds.get("full-monty")).isNotNull();
        assertThat(feeds.get("simple-batched")).isNotNull();
    }

    /**
     * The metrics autoconfig must activate in this (actuator) context: there is a {@code MeterRegistry}, so the
     * {@code MicrometerFeedEventListener} should be wired. Proves that the conditional wiring works end-to-end.
     */
    @Test
    void metricsListenerIsWiredThanksToActuator() {
        assertThat(context.getBeanNamesForType(MicrometerFeedEventListener.class)).hasSize(1);
    }

    /**
     * Likewise for the health autoconfig: actuator brings the health API, so the "atomium" contributor should be
     * there — with a component per registered feed.
     */
    @Test
    void healthContributorIsWiredThanksToActuator() {
        var contributor = context.getBean("atomiumHealthContributor",
                org.springframework.boot.health.contributor.CompositeHealthContributor.class);
        assertThat(contributor.getContributor("simple")).isNotNull();
        assertThat(contributor.getContributor("full-monty")).isNotNull();
        assertThat(contributor.getContributor("simple-batched")).isNotNull();
    }
}
