package be.wegenenverkeer.atomium.client.springboot;

import be.wegenenverkeer.atomium.client.handler.FeedConsumer;
import be.wegenenverkeer.atomium.client.handler.FeedEventListener;
import be.wegenenverkeer.atomium.client.handler.FeedRunner;
import be.wegenenverkeer.atomium.client.handler.FeedRuntime;
import be.wegenenverkeer.atomium.client.handler.Feeds;
import be.wegenenverkeer.atomium.client.handler.TestFeedRuntimes;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The status mapping of {@link AtomiumFeedHealthIndicator}: {@code DOWN} only from the threshold of consecutive
 * failures onwards, an inactive feed stays {@code UP}, and the details make the state immediately diagnosable.
 */
class AtomiumFeedHealthIndicatorTest {

    private static final int THRESHOLD = 3;

    @Test
    void aHealthyActiveFeedIsUp() {
        FeedRunner runner = runner(isInterrupted -> { }, true);
        runner.tryToStart();   // inline executor → synchronously a successful run

        Health health = new AtomiumFeedHealthIndicator(TestFeedRuntimes.withRunnerOnly(runner), THRESHOLD).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("active", true)
                .containsEntry("enabled", true)
                .containsEntry("running", false)
                .containsEntry("consecutiveFailures", 0)
                .containsKey("nextRun")
                .doesNotContainKey("lastFailure");
    }

    @Test
    void belowTheThresholdAFailingFeedStaysUpWithTheFailureAsDetail() {
        FeedRunner runner = failingRunner();
        fail(runner, THRESHOLD - 1);

        Health health = new AtomiumFeedHealthIndicator(TestFeedRuntimes.withRunnerOnly(runner), THRESHOLD).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("consecutiveFailures", THRESHOLD - 1)
                .hasEntrySatisfying("lastFailure", failure -> assertThat(failure.toString()).contains("boom"));
    }

    @Test
    void fromTheThresholdOnwardsTheFeedIsDown() {
        FeedRunner runner = failingRunner();
        fail(runner, THRESHOLD);

        Health health = new AtomiumFeedHealthIndicator(TestFeedRuntimes.withRunnerOnly(runner), THRESHOLD).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("consecutiveFailures", THRESHOLD);
    }

    @Test
    void anInactiveFeedIsUpWithActiveFalse() {
        FeedRunner runner = runner(isInterrupted -> { }, false);

        Health health = new AtomiumFeedHealthIndicator(TestFeedRuntimes.withRunnerOnly(runner), THRESHOLD).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("active", false);
    }

    /** A standby instance: the feed is wanted but not consumed here — not a health problem. */
    @Test
    void aFeedOnADisabledRuntimeIsUpWithEnabledFalse() {
        FeedRunner runner = bareRunner(isInterrupted -> { }, true);
        new Feeds(List.of(TestFeedRuntimes.withRunnerOnly(runner)), false);

        Health health = new AtomiumFeedHealthIndicator(TestFeedRuntimes.withRunnerOnly(runner), THRESHOLD).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("active", true).containsEntry("enabled", false);
    }

    @Test
    void showsLastCommitAndLastEventAsDetails() {
        OffsetDateTime commit = OffsetDateTime.parse("2026-07-28T12:00:00+02:00");
        OffsetDateTime event = OffsetDateTime.parse("2026-07-28T11:59:30+02:00");
        FeedRuntime runtime = Mockito.mock(FeedRuntime.class);
        Mockito.when(runtime.runner()).thenReturn(runner(isInterrupted -> { }, true));
        Mockito.when(runtime.lastCommit()).thenReturn(commit);
        Mockito.when(runtime.lastEvent()).thenReturn(event);

        Health health = new AtomiumFeedHealthIndicator(runtime, THRESHOLD).health();

        assertThat(health.getDetails())
                .containsEntry("lastCommit", commit.toString())
                .containsEntry("lastEvent", event.toString());
    }

    private static FeedRunner failingRunner() {
        return runner(isInterrupted -> {
            throw new IllegalStateException("boom");
        }, true);
    }

    /** Lets the runner execute {@code times} runs (synchronously); backoff {@code ZERO} → every attempt is allowed immediately. */
    private static void fail(FeedRunner runner, int times) {
        for (int i = 0; i < times; i++) {
            assertThat(runner.tryToStart()).isTrue();
        }
    }

    /** A runner in an enabled registry: only then does it run at all. */
    private static FeedRunner runner(FeedConsumer consumer, boolean activeOnStartup) {
        FeedRunner runner = bareRunner(consumer, activeOnStartup);
        new Feeds(List.of(TestFeedRuntimes.withRunnerOnly(runner)));
        return runner;
    }

    private static FeedRunner bareRunner(FeedConsumer consumer, boolean activeOnStartup) {
        return new FeedRunner("feed", Duration.ofMinutes(1), consumer, Runnable::run, activeOnStartup,
                failures -> Duration.ZERO, Clock.systemUTC(), new FeedEventListener() {
        });
    }
}
