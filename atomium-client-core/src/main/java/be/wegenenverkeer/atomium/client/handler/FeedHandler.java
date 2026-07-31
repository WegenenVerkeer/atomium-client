package be.wegenenverkeer.atomium.client.handler;

/**
 * The base SPI for consuming an Atomium feed. Do not implement this interface directly, but one of its
 * concrete variants; the framework sets up a feed consumer per handler:
 * <ul>
 *   <li>{@link EntryFeedHandler} — the common case: process the entries one by one.</li>
 *   <li>{@link SimpleBatchedProcessingFeedHandler} — process them per batch, in two phases (prepare outside
 *       the transaction, persist inside it), for a feed that produces events in bursts or processing that
 *       involves remote lookups.</li>
 * </ul>
 *
 * <p>Only {@link #getFeedId()} (plus the entry callback of the chosen variant) is mandatory; everything else has a
 * sensible default. It is deliberately an interface (with default methods) and not an abstract class, so the lib
 * does not claim the user's only superclass. The handler is <em>pure domain</em> (identity + callbacks):
 * infrastructure config (HTTP client, decoder, executor, …) lives in the {@link Feed}, not here.
 *
 * <p>The handler is <em>stateless</em>: the framework owns any buffer and intermediate state, and offers
 * everything the callback needs as a parameter. To observe the <em>lifecycle</em> of a run
 * (start, page boundary, end of feed, interruption) implements a {@link FeedEventListener} — there those events
 * are no longer the handler's concern.
 *
 * <p>The <em>content</em> {@code C} is the deserialized domain type of a feed entry; the framework
 * deserializes {@code entry.content().value()} (raw JSON) into {@code C} with the
 * {@link FeedContentDecoder} of the {@link Feed}.
 *
 * @param <C> the domain type of the entry content
 */
public interface FeedHandler<C> {

    /**
     * The unique identity of this feed. It doubles as the key in the configuration and the
     * pointer persistence, the thread name and the admin URL segment. Pick a
     * short, stable name <em>without dots</em> (a dot breaks the YAML map-key notation), e.g.
     * {@code "the-server-application"} or {@code "the-server-application-feed-a"} for multiple feeds from the same source.
     */
    String getFeedId();

    /**
     * Process a content item <em>as if</em> it appeared as an entry on the feed (e.g. to correct a failure of the
     * source application via the admin endpoint without that application having to release a fix).
     *
     * <p>Push is <em>opt-in</em>: by default this method throws an {@link UnsupportedOperationException} (which the
     * admin endpoint translates into a 400). A handler that wants to support push overrides this method
     * deliberately — unlike the regular entry callback there is no
     * {@link be.wegenenverkeer.atomium.client.protocol.AtomiumEntry} or
     * {@link be.wegenenverkeer.atomium.client.protocol.FeedPageMetadata} available here (the item was never really on
     * the feed), so the handler only has to be able to process the {@code content} itself.
     *
     * <p><b>Threading:</b> a push runs on the calling (admin request) thread, <em>not</em> on the feed thread,
     * and can therefore coincide with a running run of the same handler. Since the handler is supposed to be stateless
     * anyway, that is not a problem — but do not count on push and the regular processing callbacks never running
     * concurrently.
     */
    default void pushEntry(C content) {
        throw new UnsupportedOperationException(
                "this handler does not support pushing entries; override pushEntry(C) to support it");
    }
}
