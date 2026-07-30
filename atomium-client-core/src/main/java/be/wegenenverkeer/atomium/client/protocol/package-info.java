/**
 * The Atomium protocol/envelope model as {@code atomium-client-core} sees it: a {@link FeedPage}
 * with {@link FeedPageMetadata} and {@link AtomiumEntry}s.
 *
 * <p>The content of an entry remains a raw JSON String ({@link Content#value()}). The client library
 * does not deserialize that content; that is the responsibility of the feed consumer, which can thus
 * catch a deserialization failure per entry without breaking the whole feed.
 */
@NullMarked
package be.wegenenverkeer.atomium.client.protocol;

import org.jspecify.annotations.NullMarked;
