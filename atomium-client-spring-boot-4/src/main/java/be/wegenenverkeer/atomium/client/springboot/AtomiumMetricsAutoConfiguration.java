package be.wegenenverkeer.atomium.client.springboot;

import be.wegenenverkeer.atomium.client.handler.FeedEventListener;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Registers the {@link MicrometerFeedEventListener} so that the feed processing publishes metrics automatically.
 * Deliberately a separate auto-configuration (apart from {@link AtomiumFeedAutoConfiguration}), fully conditioned on
 * Micrometer:
 *
 * <ul>
 *   <li>{@code @ConditionalOnClass(MeterRegistry)} — Micrometer must be on the classpath (it is an <em>optional</em>
 *       dependency of this module; without Micrometer this class does not even load);</li>
 *   <li>{@code @ConditionalOnBean(MeterRegistry)} — there must be a registry in the context (typically via Actuator +
 *       a registry implementation such as prometheus). Hence the ordering after Boot's {@code CompositeMeterRegistryAutoConfiguration},
 *       which creates the registry bean (just like Boot's own metrics binders order themselves);</li>
 *   <li>{@code atomium.metrics.enabled} — on by default, but can be switched off.</li>
 * </ul>
 *
 * <p>The listener is a regular {@link FeedEventListener} bean, so the existing wiring applies it app-wide to every feed
 * — no change to the {@code FeedFactory} needed.
 */
@AutoConfiguration(afterName = {
        // Boot 4: the autoconfig that creates the MeterRegistry bean (moved out of spring-boot-actuator-autoconfigure)
        "org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration",
        // Boot 3 name as a safety net; an unknown name in afterName is ignored
        "org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration"})
@ConditionalOnClass(MeterRegistry.class)
@ConditionalOnBean(MeterRegistry.class)
@ConditionalOnProperty(name = "atomium.metrics.enabled", havingValue = "true", matchIfMissing = true)
public class AtomiumMetricsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public MicrometerFeedEventListener atomiumMicrometerFeedEventListener(MeterRegistry registry) {
        return new MicrometerFeedEventListener(registry);
    }
}
