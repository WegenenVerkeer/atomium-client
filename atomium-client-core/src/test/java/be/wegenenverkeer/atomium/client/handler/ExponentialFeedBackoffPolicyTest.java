package be.wegenenverkeer.atomium.client.handler;


import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/** The exponential backoff formula: {@code initial * multiplier^(n-1)}, capped at {@code maxInterval}. */
class ExponentialFeedBackoffPolicyTest {

    private final FeedBackoffPolicy policy =
            new ExponentialFeedBackoffPolicy(Duration.ofMinutes(1), Duration.ofHours(1), 2);

    @Test
    void growsExponentiallyUpToTheCap() {
        assertThat(policy.nextInterval(1)).isEqualTo(Duration.ofMinutes(1));   // 1 * 2^0
        assertThat(policy.nextInterval(2)).isEqualTo(Duration.ofMinutes(2));   // 1 * 2^1
        assertThat(policy.nextInterval(3)).isEqualTo(Duration.ofMinutes(4));   // 1 * 2^2
        assertThat(policy.nextInterval(4)).isEqualTo(Duration.ofMinutes(8));
        assertThat(policy.nextInterval(7)).isEqualTo(Duration.ofHours(1));     // 64m → capped at 60m
        assertThat(policy.nextInterval(100)).isEqualTo(Duration.ofHours(1));   // stays capped, no overflow
    }

    @Test
    void invalidParametersFailAtConstruction() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ExponentialFeedBackoffPolicy(Duration.ZERO, Duration.ofHours(1), 2))
                .withMessageContaining("initialInterval");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ExponentialFeedBackoffPolicy(Duration.ofMinutes(5), Duration.ofMinutes(1), 2))
                .withMessageContaining("maxInterval");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ExponentialFeedBackoffPolicy(Duration.ofMinutes(1), Duration.ofHours(1), 0.5))
                .withMessageContaining("multiplier");
    }
}
