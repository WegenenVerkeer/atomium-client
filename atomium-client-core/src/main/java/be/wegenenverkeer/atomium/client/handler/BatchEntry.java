package be.wegenenverkeer.atomium.client.handler;

import be.wegenenverkeer.atomium.client.protocol.AtomiumEntry;
import be.wegenenverkeer.atomium.client.protocol.FeedPageMetadata;

/**
 * One decoded feed entry with everything a handler callback receives about it: the metadata of the page it was
 * on, the raw entry and the deserialized content.
 *
 * <p>This is also the buffer element of the internal {@link FeedHandlerController}: it buffers the entries and
 * offers them to the handler on a flush.
 *
 * @param <C> the domain type of the entry content
 */
public record BatchEntry<C>(FeedPageMetadata pageMetadata, AtomiumEntry entry, C content) {
}
