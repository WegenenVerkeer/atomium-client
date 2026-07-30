package be.wegenenverkeer.atomium.client.fetch;

import org.jspecify.annotations.Nullable;

/**
 * Everything {@link AtomiumClient#fetch(FeedPointer)} needs to fetch the next events as efficiently as
 * possible: which page, which already delivered events to skip, and the etag for a conditional GET.
 *
 * @param pageLink      the page href relative to the feed base URL (e.g. {@code "/182"}); an empty string is the head
 * @param filterEventId if set and present on the fetched page: all entries up to and including this event are
 *                      filtered out of the result (they were already delivered)
 * @param etag          if set: sent as {@code If-None-Match} so the server can reply with a
 *                      {@code 304 Not Modified} when the page did not change
 */
public record FetchCoordinate(String pageLink, @Nullable String filterEventId, @Nullable String etag) {
}
