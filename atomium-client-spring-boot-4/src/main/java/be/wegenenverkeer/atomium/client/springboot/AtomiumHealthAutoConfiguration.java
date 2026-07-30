package be.wegenenverkeer.atomium.client.springboot;

import be.wegenenverkeer.atomium.client.handler.FeedRuntime;
import be.wegenenverkeer.atomium.client.handler.Feeds;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.CompositeHealthContributor;
import org.springframework.boot.health.contributor.HealthContributor;
import org.springframework.context.annotation.Bean;

import java.util.Map;
import java.util.TreeMap;

/**
 * Registers one health contributor "atomium" with an {@link AtomiumFeedHealthIndicator} per feed
 * ({@code /management/health} → {@code components.atomium.components.<feedId>}). Only when the Spring Boot
 * health API is on the classpath ({@code spring-boot-health} is an <em>optional</em> dependency) and
 * {@code atomium.health.enabled} is not set to {@code false}.
 *
 * <p>By default the contributor only counts towards the general health endpoint, not the liveness/readiness groups
 * (Boot default) — and it should stay that way: see the README ("Health").
 */
@AutoConfiguration(after = AtomiumFeedAutoConfiguration.class)
@ConditionalOnClass(HealthContributor.class)
@ConditionalOnProperty(name = "atomium.health.enabled", havingValue = "true", matchIfMissing = true)
public class AtomiumHealthAutoConfiguration {

    @Bean
    @ConditionalOnBean(Feeds.class)
    @ConditionalOnMissingBean(name = "atomiumHealthContributor")
    public HealthContributor atomiumHealthContributor(Feeds feeds, AtomiumProperties atomiumProperties) {
        int threshold = atomiumProperties.health().failureThreshold();
        Map<String, HealthContributor> perFeed = new TreeMap<>();
        for (FeedRuntime feed : feeds.all()) {
            perFeed.put(feed.feedId(), new AtomiumFeedHealthIndicator(feed, threshold));
        }
        return CompositeHealthContributor.fromMap(perFeed);
    }
}
