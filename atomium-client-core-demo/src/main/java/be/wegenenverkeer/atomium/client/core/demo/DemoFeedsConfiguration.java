package be.wegenenverkeer.atomium.client.core.demo;

import be.wegenenverkeer.atomium.client.handler.FeedRuntime;
import be.wegenenverkeer.atomium.client.handler.Feeds;
import be.wegenenverkeer.atomium.client.handler.SimpleFeedScheduler;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * The feed machinery around the per-feed assemblies (see {@code SimpleDemoConfiguration} and
 * {@code FullMontyDemoConfiguration}): the {@link Feeds} registry and the bundled {@link SimpleFeedScheduler}.
 * The scheduler only starts once the web server is running ({@link ApplicationReadyEvent}) — after all, the
 * feeds poll their own {@link DemoFeedEndpoint}; on shutdown the {@code destroyMethod}s tear everything down cleanly (ticks
 * stop, runs in progress stop after their next commit point).
 */
@Configuration
class DemoFeedsConfiguration {

    @Bean(destroyMethod = "close")
    Feeds feeds(List<FeedRuntime> runtimes) {
        return new Feeds(runtimes);
    }

    @Bean(destroyMethod = "close")
    SimpleFeedScheduler feedScheduler(Feeds feeds) {
        return new SimpleFeedScheduler(feeds);
    }

    @Bean
    ApplicationListener<ApplicationReadyEvent> startFeedScheduler(SimpleFeedScheduler feedScheduler) {
        return event -> feedScheduler.start();
    }
}
