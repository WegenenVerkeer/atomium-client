package be.wegenenverkeer.atomium.client.handler;

import java.util.function.BooleanSupplier;

/**
 * The pure-reader role of a feed: read the feed from the persisted pointer up to the head, and stop cleanly
 * as soon as {@code isInterrupted} becomes {@code true}. Scheduling and start/stop lifecycle deliberately live
 * <em>outside</em> this role (see {@link FeedRunner}); {@code isInterrupted} is the only coupling to it.
 *
 * <p>The default implementation is {@link FeedConsumerImpl}.
 */
@FunctionalInterface
public interface FeedConsumer {

    /**
     * Read the feed from the (persisted) pointer up to the head. After every commit point (per entry, per wrapped-up batch or on a boundary)
     * {@code isInterrupted} is consulted; if it is {@code true}, the consumer stops cleanly. Because the
     * pointer has already been persisted at that point, the next run simply resumes where this one stopped.
     */
    void run(BooleanSupplier isInterrupted);
}
