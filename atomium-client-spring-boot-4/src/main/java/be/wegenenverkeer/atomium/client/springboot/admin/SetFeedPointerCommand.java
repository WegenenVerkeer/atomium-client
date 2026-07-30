package be.wegenenverkeer.atomium.client.springboot.admin;

import be.wegenenverkeer.atomium.client.fetch.EventCoordinate;
import be.wegenenverkeer.atomium.client.fetch.FeedPointer;
import org.jspecify.annotations.Nullable;

/**
 * Request body to move the read position of a feed via the admin endpoint. The caller supplies the
 * page and (optionally) the last processed event; it does not need to know the internal fetch optimization.
 *
 * @param pageLink the page href (required, not empty)
 * @param eventId  the last processed event on that page, or {@code null} to read the page from the beginning
 */
public record SetFeedPointerCommand(String pageLink, @Nullable String eventId) {

    /**
     * Converts this command into a {@link FeedPointer}. With an {@code eventId} the feed resumes just after that
     * event ({@link FeedPointer#resumeAfter}); without an {@code eventId} it reads the complete page from the
     * beginning.
     *
     * @throws AtomiumAdminValidationException if {@code pageLink} is missing or empty
     */
    public FeedPointer toFeedPointer() {
        if (pageLink == null || pageLink.isBlank()) {
            throw new AtomiumAdminValidationException("pageLink is required.");
        }
        return eventId != null
                ? FeedPointer.resumeAfter(new EventCoordinate(pageLink, eventId))
                : new FeedPointer(pageLink);
    }
}
