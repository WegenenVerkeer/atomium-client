package be.wegenenverkeer.atomium.client.springboot.admin;

import be.wegenenverkeer.atomium.client.fetch.EventCoordinate;
import be.wegenenverkeer.atomium.client.fetch.FeedPointer;
import be.wegenenverkeer.atomium.client.springboot.FeedPointerType;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Validation of a {@link SetFeedPointerCommand} and its translation into a {@link FeedPointer}: the type names the
 * kind (absent = {@code POINTER}), a {@code POINTER} needs a page link and resumes just after the event when one is
 * given, and {@code OLDEST}/{@code NOW} carry no position of their own.
 */
class SetFeedPointerCommandTest {

    @Nested
    class Pointer {

        @Test
        void isTheDefaultWhenTheTypeIsAbsent() {
            assertThat(new SetFeedPointerCommand(null, "/5", null).resolvedType()).isEqualTo(FeedPointerType.POINTER);
            assertThat(new SetFeedPointerCommand("/5", null).resolvedType()).isEqualTo(FeedPointerType.POINTER);
        }

        @Test
        void withEventIdResumesJustAfterThatEvent() {
            FeedPointer pointer = new SetFeedPointerCommand("/5", "id-042").toFeedPointer();

            assertThat(pointer).isEqualTo(FeedPointer.resumeAfter(new EventCoordinate("/5", "id-042")));
        }

        @Test
        void withoutEventIdReadsThePageFromTheBeginning() {
            FeedPointer pointer = new SetFeedPointerCommand("/5", null).toFeedPointer();

            assertThat(pointer).isEqualTo(new FeedPointer("/5"));
        }

        @Test
        void emptyOrMissingPageLinkIsAValidationError() {
            assertThatExceptionOfType(AtomiumAdminValidationException.class)
                    .isThrownBy(() -> new SetFeedPointerCommand("   ", "id-1").resolvedType());
            assertThatExceptionOfType(AtomiumAdminValidationException.class)
                    .isThrownBy(() -> new SetFeedPointerCommand("POINTER", null, null).resolvedType());
        }
    }

    @Nested
    class OldestAndNow {

        /** The type is the enum constant, spelled as such; a lower-case spelling is an unknown type. */
        @Test
        void areTheEnumConstants() {
            assertThat(new SetFeedPointerCommand("OLDEST", null, null).resolvedType()).isEqualTo(FeedPointerType.OLDEST);
            assertThat(new SetFeedPointerCommand(" NOW ", null, null).resolvedType()).isEqualTo(FeedPointerType.NOW);
            assertThatExceptionOfType(AtomiumAdminValidationException.class)
                    .isThrownBy(() -> new SetFeedPointerCommand("oldest", null, null).resolvedType())
                    .withMessageContaining("'OLDEST', 'NOW' or 'POINTER'");
        }

        @Test
        void carryNoPositionOfTheirOwn() {
            assertThatExceptionOfType(AtomiumAdminValidationException.class)
                    .isThrownBy(() -> new SetFeedPointerCommand("OLDEST", "/5", null).resolvedType())
                    .withMessageContaining("POINTER");
            assertThatExceptionOfType(AtomiumAdminValidationException.class)
                    .isThrownBy(() -> new SetFeedPointerCommand("NOW", null, "id-1").resolvedType());
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> new SetFeedPointerCommand("NOW", null, null).toFeedPointer());
        }
    }

    @Test
    void anUnknownTypeIsAValidationError() {
        assertThatExceptionOfType(AtomiumAdminValidationException.class)
                .isThrownBy(() -> new SetFeedPointerCommand("head", null, null).resolvedType())
                .withMessageContaining("head");
    }
}
