package be.wegenenverkeer.atomium.client.fetch;

import org.jspecify.annotations.Nullable;

/**
 * The read position of a feed: everything the consumer keeps in order to know <em>where it stands</em> and <em>how it
 * continues</em>. Deliberately two separate concepts:
 *
 * <ul>
 *   <li>{@link #lastEvent()} — the coordinates (page + id) of the <strong>last processed event</strong>. Moves
 *       only when an event is actually processed; {@code null} as long as nothing has been processed yet.</li>
 *   <li>{@link #nextFetch()} — where {@link AtomiumClient#fetch(FeedPointer)} continues (page, filter, etag).
 *       Moves on every fetch, including a fetch that yields nothing.</li>
 * </ul>
 *
 * <p>The value object is deliberately small and fully self-describing, so that it can be recovered without additional
 * state and even be moved at runtime (e.g. via an admin endpoint).
 *
 * @param lastEvent     the last processed event, or {@code null} as long as nothing has been processed yet
 * @param nextFetch the coordinate for the next fetch
 */
public record FeedPointer(@Nullable EventCoordinate lastEvent, FetchCoordinate nextFetch) {

    /** A start position on a page, without a processed event, without filter and without etag (delivers the whole page). */
    public FeedPointer(String pageLink) {
        this(null, new FetchCoordinate(pageLink, null, null));
    }

    /**
     * Rebuild a {@link FeedPointer} from the four persisted fields. The {@code filterEventId} of the fetch is
     * derived: it equals the {@code lastEventId} as long as we fetch on the same page as where the last
     * processed event is, and is empty otherwise (we have just jumped to a younger page).
     */
    public FeedPointer(@Nullable String lastEventPageLink, @Nullable String lastEventId,
                       String nextFetchPageLink, @Nullable String nextFetchPageEtag) {
        this(coordinateOf(lastEventPageLink, lastEventId),
                new FetchCoordinate(nextFetchPageLink,
                        filterOf(lastEventPageLink, lastEventId, nextFetchPageLink), nextFetchPageEtag));
    }

    /**
     * A pointer that resumes the feed <em>just after</em> a processed event: re-poll the page of that event and filter
     * out everything up to and including that event. Handy for building a complete pointer from just the
     * {@code lastEvent} coordinates (e.g. via the admin endpoint) without having to know the fetch optimization.
     */
    public static FeedPointer resumeAfter(EventCoordinate lastEvent) {
        return new FeedPointer(lastEvent, new FetchCoordinate(lastEvent.pageLink(), lastEvent.eventId(), null));
    }

    private static @Nullable EventCoordinate coordinateOf(@Nullable String pageLink, @Nullable String eventId) {
        return (pageLink != null && eventId != null) ? new EventCoordinate(pageLink, eventId) : null;
    }

    private static @Nullable String filterOf(@Nullable String lastEventPageLink, @Nullable String lastEventId,
                                             String nextFetchPageLink) {
        return (lastEventPageLink != null && lastEventId != null && nextFetchPageLink.equals(lastEventPageLink))
                ? lastEventId
                : null;
    }
}
