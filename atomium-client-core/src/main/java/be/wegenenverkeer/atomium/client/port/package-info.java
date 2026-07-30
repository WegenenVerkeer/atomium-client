/**
 * The two ports an application (or an adapter module) implements to reach a feed:
 * {@link be.wegenenverkeer.atomium.client.port.FeedHttpClient} (the HTTP GETs) and
 * {@link be.wegenenverkeer.atomium.client.port.FeedPageDecoder} (the JSON envelope into a
 * {@code FeedPage}).
 */
@NullMarked
package be.wegenenverkeer.atomium.client.port;

import org.jspecify.annotations.NullMarked;
