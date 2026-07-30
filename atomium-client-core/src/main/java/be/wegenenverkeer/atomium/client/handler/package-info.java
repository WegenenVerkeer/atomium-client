/**
 * The high-level <b>handler API</b>: implement a
 * {@link be.wegenenverkeer.atomium.client.handler.FeedHandler} (per entry or per batch), bundle it with the
 * building blocks in a {@link be.wegenenverkeer.atomium.client.handler.Feed}, and let the machinery assembled
 * by {@link be.wegenenverkeer.atomium.client.handler.FeedRuntime} walk the feed —
 * with batches, transactions, pointer persistence, backoff and observability events.
 *
 * <p>A worked example can be found in the module's README.
 */
@NullMarked
package be.wegenenverkeer.atomium.client.handler;

import org.jspecify.annotations.NullMarked;
