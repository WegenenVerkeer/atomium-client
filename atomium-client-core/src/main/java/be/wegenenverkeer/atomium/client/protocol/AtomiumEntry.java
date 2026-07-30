package be.wegenenverkeer.atomium.client.protocol;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * An entry (event) in a feed page.
 *
 * @param id      the unique id of the event; used for the read position and deduplication
 * @param updated the time at which the event was added to the feed
 * @param content the content of the event; {@link Content#value()} is raw JSON
 * @param links   any HATEOAS links for the entry
 */
public record AtomiumEntry(String id, OffsetDateTime updated, Content content, List<Link> links) {
}
