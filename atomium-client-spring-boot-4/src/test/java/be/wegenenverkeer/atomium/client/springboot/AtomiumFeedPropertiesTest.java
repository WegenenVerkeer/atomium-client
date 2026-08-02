package be.wegenenverkeer.atomium.client.springboot;

import be.wegenenverkeer.atomium.client.springboot.AtomiumFeedProperties.Backoff;
import be.wegenenverkeer.atomium.client.springboot.AtomiumFeedProperties.InitialFeedPointer;
import be.wegenenverkeer.atomium.client.springboot.AtomiumFeedProperties.Processing;
import be.wegenenverkeer.atomium.client.springboot.AtomiumFeedProperties.InitialFeedPointer.Type;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * The validations of {@link AtomiumFeedProperties} and its sub-records: invalid config fails at binding time
 * with the property name in the message, instead of failing silently later on.
 */
class AtomiumFeedPropertiesTest {

    @Nested
    class QueryInterval {

        @Test
        void mustBePositive() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> feedProperties(Duration.ZERO))
                    .withMessageContaining("query-interval");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> feedProperties(Duration.ofSeconds(-1)))
                    .withMessageContaining("query-interval");
        }

        @Test
        void positiveIsOk() {
            assertThatCode(() -> feedProperties(Duration.ofSeconds(30))).doesNotThrowAnyException();
        }

        private static AtomiumFeedProperties feedProperties(Duration queryInterval) {
            return new AtomiumFeedProperties("http://localhost/feed", false, queryInterval, null,
                    new Backoff(Duration.ofMinutes(1), Duration.ofHours(1), 2), new Processing(null, null));
        }
    }

    @Nested
    class BackoffValidation {

        @Test
        void initialIntervalMustBePositive() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new Backoff(Duration.ZERO, Duration.ofHours(1), 2))
                    .withMessageContaining("backoff.initial-interval");
        }

        @Test
        void maxIntervalMustNotBeSmallerThanInitialInterval() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new Backoff(Duration.ofMinutes(5), Duration.ofMinutes(1), 2))
                    .withMessageContaining("backoff.max-interval");
        }

        @Test
        void multiplierMustBeAtLeast1() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new Backoff(Duration.ofMinutes(1), Duration.ofHours(1), 0.5))
                    .withMessageContaining("backoff.multiplier");
        }

        @Test
        void validBackoffIsOk() {
            assertThatCode(() -> new Backoff(Duration.ofMinutes(1), Duration.ofMinutes(1), 1))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    class ProcessingValidation {

        @Test
        void lowerBoundIs1() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new Processing(0, null))
                    .withMessageContaining("processing.max-size");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new Processing(null, 0))
                    .withMessageContaining("processing.max-uncommitted-pages");
        }

        @Test
        void emptyIsOkTheCoreDefaultsApply() {
            assertThatCode(() -> new Processing(null, null)).doesNotThrowAnyException();
        }
    }

    @Nested
    class InitialFeedPointerValidation {

        @Test
        void typeIsRequired() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new InitialFeedPointer(null, null))
                    .withMessageContaining("initial-feed-pointer.type");
        }

        @Test
        void typePointerRequiresPageLink() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new InitialFeedPointer(Type.POINTER, null))
                    .withMessageContaining("page-link");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new InitialFeedPointer(Type.POINTER, "  "))
                    .withMessageContaining("page-link");
        }

        @Test
        void pageLinkWithAnotherTypeIsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new InitialFeedPointer(Type.OLDEST, "/182"))
                    .withMessageContaining("page-link");
        }

        @Test
        void validCombinationsAreOk() {
            assertThatCode(() -> new InitialFeedPointer(Type.POINTER, "/182")).doesNotThrowAnyException();
            assertThatCode(() -> new InitialFeedPointer(Type.NOW, null)).doesNotThrowAnyException();
        }
    }
}
