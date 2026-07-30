package be.wegenenverkeer.atomium.client.springboot;

import be.wegenenverkeer.atomium.client.handler.FeedRunFailure;
import be.wegenenverkeer.atomium.client.handler.FeedRunner;
import be.wegenenverkeer.atomium.client.handler.FeedRuntime;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

import java.time.OffsetDateTime;

/**
 * The health of one feed, as a snapshot of the {@link FeedRuntime} state: {@code DOWN} from
 * {@code failureThreshold} <em>consecutive</em> failed runs onward, otherwise {@code UP} — with the details
 * (active, running, counter, next run, last commit/event, last failure) for immediate diagnosis.
 * {@code lastCommit} and {@code lastEvent} also surface the <em>silently</em> stalled feed: no failure at all,
 * but (unexpectedly) nothing is being published or processed anymore.
 *
 * <p>An <em>inactive</em> feed is deliberately {@code UP} (with {@code active: false} as a detail): a deliberately
 * deactivated feed (jobs pod, migration) must not make a pod unhealthy; accidentally inactive is already
 * signaled by the scheduler's startup WARN.
 */
public class AtomiumFeedHealthIndicator implements HealthIndicator {

    private final FeedRuntime feed;
    private final int failureThreshold;

    public AtomiumFeedHealthIndicator(FeedRuntime feed, int failureThreshold) {
        this.feed = feed;
        this.failureThreshold = failureThreshold;
    }

    @Override
    public Health health() {
        FeedRunner runner = feed.runner();
        int failures = runner.consecutiveFailures();
        Health.Builder health = failures >= failureThreshold ? Health.down() : Health.up();
        health.withDetail("active", runner.isActive())
                .withDetail("running", runner.isRunning())
                .withDetail("consecutiveFailures", failures);
        detailIfPresent(health, "nextRun", runner.nextRun());
        detailIfPresent(health, "lastCommit", feed.lastCommit());
        detailIfPresent(health, "lastEvent", feed.lastEvent());
        FeedRunFailure failure = runner.lastFailure();
        if (failure != null) {
            health.withDetail("lastFailure", describe(failure));
        }
        return health.build();
    }

    private static void detailIfPresent(Health.Builder health, String name, @Nullable OffsetDateTime time) {
        if (time != null) {
            health.withDetail(name, time.toString());
        }
    }

    private static String describe(FeedRunFailure failure) {
        String entryContext = failure.entryId() == null ? ""
                : " (entry '%s', phase %s)".formatted(failure.entryId(), failure.phase());
        return failure.cause() + entryContext;
    }
}
