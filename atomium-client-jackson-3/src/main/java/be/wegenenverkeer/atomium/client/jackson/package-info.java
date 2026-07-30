/**
 * Jackson adapters for {@code atomium-client-core}, on top of Jackson 3 ({@code tools.jackson}):
 *
 * <ul>
 *   <li>{@link be.wegenenverkeer.atomium.client.jackson.JacksonFeedPageDecoder} — the
 *       {@link be.wegenenverkeer.atomium.client.port.FeedPageDecoder}: decodes the Atomium envelope and keeps
 *       {@code content.value} raw. It creates its <em>own</em> {@code JsonMapper} and is therefore fully
 *       independent of the application's Jackson config.</li>
 *   <li>{@link be.wegenenverkeer.atomium.client.jackson.JacksonFeedContentDecoder} — the
 *       {@code FeedContentDecoder} for the handler API: derives the content type from the handler type and
 *       deserializes the entry content with the {@code JsonMapper} <em>passed in</em> — entry content <em>is</em>
 *       domain content.</li>
 * </ul>
 */
@NullMarked
package be.wegenenverkeer.atomium.client.jackson;

import org.jspecify.annotations.NullMarked;
