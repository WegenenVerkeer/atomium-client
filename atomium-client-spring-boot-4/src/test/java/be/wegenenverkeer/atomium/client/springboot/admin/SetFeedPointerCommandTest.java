package be.wegenenverkeer.atomium.client.springboot.admin;

import be.wegenenverkeer.atomium.client.fetch.EventCoordinate;
import be.wegenenverkeer.atomium.client.fetch.FeedPointer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Translation of a {@link SetFeedPointerCommand} into a {@link FeedPointer}: with an eventId the feed resumes just
 * after that event, without an eventId it reads the page from the beginning, and an empty pageLink is a validation
 * error.
 */
class SetFeedPointerCommandTest {

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
                .isThrownBy(() -> new SetFeedPointerCommand("   ", "id-1").toFeedPointer());
        assertThatExceptionOfType(AtomiumAdminValidationException.class)
                .isThrownBy(() -> new SetFeedPointerCommand(null, "id-1").toFeedPointer());
    }
}
