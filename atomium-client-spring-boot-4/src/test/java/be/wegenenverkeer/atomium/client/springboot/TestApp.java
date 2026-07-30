package be.wegenenverkeer.atomium.client.springboot;

import be.wegenenverkeer.atomium.client.handler.FeedHandler;

import be.wegenenverkeer.atomium.client.springboot.admin.AtomiumAdminEndpoint;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * Minimal Spring Boot application for the integration tests of the generic module: component scan (picks up the
 * test {@link FeedHandler}s) + auto-configuration. Provides its own test {@link FeedRestClientBuilders} — the
 * mandatory (narrow) seam the generic module leaves open — with a simple RestClient to the WireMock feed. This
 * proves that the machinery works with just this one bean: content mapper, executor and backoff come as
 * framework defaults from the module itself.
 *
 * <p>The {@code @RestController} {@link AtomiumAdminEndpoint} lives in a subpackage of this test app and would thus
 * be component-scanned (not in a real app, which has a different base package). We exclude it, so it is only
 * registered via the auto-configuration (web + {@code atomium.admin.enabled}). The first two filters are
 * Boot's defaults.
 */
@SpringBootApplication
@ComponentScan(excludeFilters = {
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class),
        @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = AtomiumAdminEndpoint.class)
})
public class TestApp {

    @Bean
    FeedRestClientBuilders testFeedRestClientBuilders() {
        return new TestFeedRestClientBuilders();
    }
}
