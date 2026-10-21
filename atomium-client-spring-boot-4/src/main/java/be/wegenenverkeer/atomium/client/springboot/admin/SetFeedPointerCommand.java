package be.wegenenverkeer.atomium.client.springboot.admin;

import be.wegenenverkeer.atomium.client.fetch.EventCoordinate;
import be.wegenenverkeer.atomium.client.fetch.FeedPointer;
import be.wegenenverkeer.atomium.client.springboot.FeedPointerType;
import org.jspecify.annotations.Nullable;

/**
 * Request body to move the read position of a feed via the admin endpoint. Three kinds, the
 * {@link FeedPointerType}s:
 * <ul>
 *   <li>{@code "type": "OLDEST"} — the full history again;</li>
 *   <li>{@code "type": "NOW"} — skip what is on the feed now, only new events;</li>
 *   <li>{@code "type": "POINTER"} (the default when absent) — a known position: the {@code pageLink} and optionally
 *       the last processed {@code eventId} on that page; the caller does not need to know the internal fetch
 *       optimization.</li>
 * </ul>
 *
 * @param type     {@code OLDEST}, {@code NOW} or {@code POINTER}; absent = {@code POINTER}
 * @param pageLink only for {@code POINTER} (and then required): the page href
 * @param eventId  only for {@code POINTER}, optional: the last processed event on that page, or {@code null} to
 *                 read the page from the beginning
 */
public record SetFeedPointerCommand(@Nullable String type, @Nullable String pageLink, @Nullable String eventId) {

    /** A {@code POINTER} command: the type is implied. */
    public SetFeedPointerCommand(String pageLink, @Nullable String eventId) {
        this(FeedPointerType.POINTER.name(), pageLink, eventId);
    }

    /**
     * The validated kind of this command.
     *
     * @throws AtomiumAdminValidationException on an unknown type, a {@code POINTER} without {@code pageLink}, or an
     *                                         {@code OLDEST}/{@code NOW} with a {@code pageLink} or {@code eventId}
     */
    public FeedPointerType resolvedType() {
        // the type is a String, not the enum, so that an unknown value gives a 400 with a message that names the
        // three kinds instead of Jackson's generic deserialization error
        FeedPointerType resolved;
        if (type == null || type.isBlank()) {
            resolved = FeedPointerType.POINTER;
        } else {
            try {
                resolved = FeedPointerType.valueOf(type.trim());
            } catch (IllegalArgumentException e) {
                throw new AtomiumAdminValidationException(
                        "type must be 'OLDEST', 'NOW' or 'POINTER', was '%s'.".formatted(type));
            }
        }
        if (resolved == FeedPointerType.POINTER) {
            if (pageLink == null || pageLink.isBlank()) {
                throw new AtomiumAdminValidationException("pageLink is required.");
            }
        } else if (pageLink != null || eventId != null) {
            throw new AtomiumAdminValidationException(
                    "pageLink and eventId are only meaningful with type 'POINTER', not with '%s'.".formatted(type));
        }
        return resolved;
    }

    /**
     * Converts a {@code POINTER} command into a {@link FeedPointer}. With an {@code eventId} the feed resumes just
     * after that event ({@link FeedPointer#resumeAfter}); without an {@code eventId} it reads the complete page from
     * the beginning.
     *
     * @throws AtomiumAdminValidationException if this is not a valid {@code POINTER} command
     * @throws IllegalStateException           on an {@code OLDEST} or {@code NOW} command: those have no pointer of
     *                                         their own, the source decides
     */
    public FeedPointer toFeedPointer() {
        if (resolvedType() != FeedPointerType.POINTER) {
            throw new IllegalStateException("only a 'POINTER' command carries a feed pointer");
        }
        return eventId != null
                ? FeedPointer.resumeAfter(new EventCoordinate(pageLink, eventId))
                : new FeedPointer(pageLink);
    }
}
