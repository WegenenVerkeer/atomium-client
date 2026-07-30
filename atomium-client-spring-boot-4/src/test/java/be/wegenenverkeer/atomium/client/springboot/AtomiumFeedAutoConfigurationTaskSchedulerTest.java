package be.wegenenverkeer.atomium.client.springboot;

import be.wegenenverkeer.atomium.client.handler.FeedPointerRepository;
import be.wegenenverkeer.atomium.client.handler.InMemoryFeedPointerRepository;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards that the auto-configuration does not contribute a {@link TaskScheduler} bean: the tick pool is an internal
 * detail of the {@link FeedScheduler}. If such a bean did exist, Boot's default {@code taskScheduler} (for
 * {@code @Scheduled}) would back off — app tasks would then silently run on the atomium pool — and every unqualified
 * {@code TaskScheduler} injection in an app with its own scheduler would become ambiguous.
 */
class AtomiumFeedAutoConfigurationTaskSchedulerTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    AtomiumFeedAutoConfiguration.class, TaskSchedulingAutoConfiguration.class))
            .withUserConfiguration(MinimalAppConfig.class);

    @Test
    void theAutoConfigDoesNotContributeATaskSchedulerBean() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(FeedScheduler.class);
            assertThat(context).doesNotHaveBean(TaskScheduler.class);
        });
    }

    @Test
    void bootsDefaultTaskSchedulerRemainsAvailableForScheduled() {
        runner.withUserConfiguration(WithScheduling.class)
                .run(context -> assertThat(context)
                        .hasSingleBean(TaskScheduler.class)
                        .hasBean("taskScheduler"));
    }

    /** The minimum an app has to provide to let the auto-config start up (without feeds/handlers). */
    @Configuration
    static class MinimalAppConfig {

        @Bean
        FeedRestClientBuilders feedRestClientBuilders() {
            return (feedId, properties) -> RestClient.builder();
        }

        @Bean
        FeedPointerRepository feedPointerRepository() {
            return new InMemoryFeedPointerRepository();
        }

        @Bean
        PlatformTransactionManager transactionManager() {
            return Mockito.mock(PlatformTransactionManager.class);
        }
    }

    @Configuration
    @EnableScheduling
    static class WithScheduling {
    }
}
