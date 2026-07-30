package be.wegenenverkeer.atomium.client.springboot.admin;

import be.wegenenverkeer.atomium.client.fetch.EventCoordinate;
import be.wegenenverkeer.atomium.client.fetch.FeedPointer;
import org.jspecify.annotations.Nullable;

/**
 * The (possibly absent) persisted read position of one feed.
 *
 * @param feedId  the feed
 * @param pointer the stored position, or {@code null} if there is none yet (the feed has not started yet)
 */
public record FeedPointerDto(String feedId, @Nullable Position pointer) {

    public static FeedPointerDto of(String feedId, @Nullable FeedPointer pointer) {
        return new FeedPointerDto(feedId, pointer == null ? null : Position.of(pointer));
    }

    /**
     * The coordinates of the last processed event ({@code lastEvent*}, empty as long as nothing has been processed
     * yet) and where the next fetch begins ({@code nextFetch*}).
     */
    public record Position(
            @Nullable String lastEventPageLink,
            @Nullable String lastEventId,
            String nextFetchPageLink,
            @Nullable String nextFetchPageEtag) {

        static Position of(FeedPointer pointer) {
            EventCoordinate lastEvent = pointer.lastEvent();
            return new Position(
                    lastEvent == null ? null : lastEvent.pageLink(),
                    lastEvent == null ? null : lastEvent.eventId(),
                    pointer.nextFetch().pageLink(),
                    pointer.nextFetch().etag());
        }
    }
}
