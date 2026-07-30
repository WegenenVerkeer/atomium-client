package be.wegenenverkeer.atomium.client.springboot;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The conditions of {@link AtomiumMetricsAutoConfiguration}: the metrics listener only appears when there is a
 * {@link MeterRegistry} and the feature is on. Tested with an {@link ApplicationContextRunner} — the idiomatic
 * way to test auto-config conditions without starting a full context.
 */
class AtomiumMetricsAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtomiumMetricsAutoConfiguration.class));

    @Test
    void registersTheListenerIfThereIsAMeterRegistry() {
        runner.withUserConfiguration(MeterRegistryConfig.class)
                .run(context -> assertThat(context).hasSingleBean(MicrometerFeedEventListener.class));
    }

    @Test
    void noListenerWithoutMeterRegistry() {
        runner.run(context -> assertThat(context).doesNotHaveBean(MicrometerFeedEventListener.class));
    }

    @Test
    void noListenerIfMetricsIsDisabled() {
        runner.withUserConfiguration(MeterRegistryConfig.class)
                .withPropertyValues("atomium.metrics.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(MicrometerFeedEventListener.class));
    }

    @Configuration
    static class MeterRegistryConfig {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}
