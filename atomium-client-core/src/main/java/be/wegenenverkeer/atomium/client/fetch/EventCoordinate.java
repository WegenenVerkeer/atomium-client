package be.wegenenverkeer.atomium.client.fetch;

/**
 * The coordinates of one event in the feed: which page it is on and which id it has. Used in
 * {@link FeedPointer#lastEvent()} to track the last processed event.
 *
 * @param pageLink the page href relative to the feed base URL (e.g. {@code "/182"})
 * @param eventId  the id of the event on that page
 */
public record EventCoordinate(String pageLink, String eventId) {
}
