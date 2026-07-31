package be.wegenenverkeer.atomium.client.handler;

import be.wegenenverkeer.atomium.client.protocol.AtomiumEntry;
import be.wegenenverkeer.atomium.client.protocol.FeedPageMetadata;

/**
 * One decoded feed entry with everything the framework shares about it with a handler: the metadata of the
 * page it was on, the raw entry (id, updated, links, raw content) and the deserialized content.
 *
 * @param <C> the domain type of the entry content
 */
public record ProcessingEntry<C>(FeedPageMetadata pageMetadata, AtomiumEntry entry, C content) {
}
