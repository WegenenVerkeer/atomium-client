package be.wegenenverkeer.atomium.client.springboot;

import be.wegenenverkeer.atomium.client.handler.Feeds;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.health.contributor.HealthContributor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The conditions of {@link AtomiumHealthAutoConfiguration}: the contributor only appears with a
 * {@link Feeds} registry and as long as {@code atomium.health.enabled} is not set to {@code false}.
 */
class AtomiumHealthAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtomiumHealthAutoConfiguration.class));

    @Test
    void registersTheContributorIfThereIsAFeedsRegistry() {
        runner.withUserConfiguration(FeedsConfig.class)
                .run(context -> assertThat(context).hasBean("atomiumHealthContributor"));
    }

    @Test
    void noContributorWithoutFeedsRegistry() {
        runner.run(context -> assertThat(context).doesNotHaveBean(HealthContributor.class));
    }

    @Test
    void noContributorIfHealthIsDisabled() {
        runner.withUserConfiguration(FeedsConfig.class)
                .withPropertyValues("atomium.health.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(HealthContributor.class));
    }

    @Configuration
    static class FeedsConfig {

        @Bean
        Feeds feeds() {
            return new Feeds(List.of());
        }

        @Bean
        AtomiumProperties atomiumProperties() {
            return new AtomiumProperties(Map.of(),
                    new AtomiumProperties.Admin(false, true), new AtomiumProperties.Health(true, 3));
        }
    }
}
