package be.wegenenverkeer.atomium.client.protocol;

import be.wegenenverkeer.atomium.client.port.FeedPageDecoder;

import java.util.List;

/**
 * A feed page: the {@link FeedPageMetadata} <em>plus</em> the {@link AtomiumEntry}s.
 *
 * <p>The entries are in protocol order, i.e. <strong>youngest first</strong> (as in the JSON).
 * It is {@code AtomiumClient} that reverses them to oldest first for the consumer.
 *
 * <p>Produced by a {@link FeedPageDecoder}.
 */
public record FeedPage(FeedPageMetadata metadata, List<AtomiumEntry> entries) {
}
