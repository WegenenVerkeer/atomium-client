package be.wegenenverkeer.atomium.client.handler;

import java.time.Duration;

/**
 * The default {@link FeedBackoffPolicy}: exponential backoff with a cap. The wait time after the {@code n}-th
 * consecutive failure is {@code initialInterval * multiplier^(n-1)}, capped at {@code maxInterval}.
 * Default 1m → 2m → 4m → … → 1h.
 */
public final class ExponentialFeedBackoffPolicy implements FeedBackoffPolicy {

    private final Duration initialInterval;
    private final Duration maxInterval;
    private final double multiplier;

    public ExponentialFeedBackoffPolicy(Duration initialInterval, Duration maxInterval, double multiplier) {
        if (initialInterval.isZero() || initialInterval.isNegative()) {
            throw new IllegalArgumentException("initialInterval must be positive, was " + initialInterval);
        }
        if (maxInterval.compareTo(initialInterval) < 0) {
            throw new IllegalArgumentException(
                    "maxInterval (%s) must not be smaller than initialInterval (%s)"
                            .formatted(maxInterval, initialInterval));
        }
        if (multiplier < 1) {
            throw new IllegalArgumentException("multiplier must be at least 1, was " + multiplier);
        }
        this.initialInterval = initialInterval;
        this.maxInterval = maxInterval;
        this.multiplier = multiplier;
    }

    @Override
    public Duration nextInterval(int consecutiveFailures) {
        int exponent = Math.max(0, consecutiveFailures - 1);
        // calculate in double and cap before the conversion, so a large exponent does not overflow
        double millis = initialInterval.toMillis() * Math.pow(multiplier, exponent);
        long capped = (long) Math.min(millis, (double) maxInterval.toMillis());
        return Duration.ofMillis(capped);
    }
}
