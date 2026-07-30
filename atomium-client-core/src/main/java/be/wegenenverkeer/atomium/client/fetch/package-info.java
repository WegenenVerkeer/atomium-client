/**
 * The low-level <b>fetch API</b>: a stateless {@link be.wegenenverkeer.atomium.client.fetch.AtomiumClient}
 * that fetches pages of an Atomium feed using a movable read position
 * ({@link be.wegenenverkeer.atomium.client.fetch.FeedPointer}). If you prefer to have the feed processed
 * declaratively, use the handler API ({@link be.wegenenverkeer.atomium.client.handler}).
 *
 * <p>A protocol overview and a worked example are in the module's README.
 */
@NullMarked
package be.wegenenverkeer.atomium.client.fetch;

import org.jspecify.annotations.NullMarked;
