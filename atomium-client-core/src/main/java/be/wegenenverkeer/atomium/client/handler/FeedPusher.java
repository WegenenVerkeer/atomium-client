package be.wegenenverkeer.atomium.client.handler;

/**
 * Opt-in capability of a {@link FeedHandler}: process a content item <em>as if</em> it appeared as an entry
 * on the feed (e.g. to correct a failure of the source application via the admin endpoint, without that
 * application having to release a fix). A handler declares the intent by implementing this interface next to
 * its handler variant:
 *
 * <pre>{@code
 * @Component
 * class MyEventFeedHandler implements EntryFeedHandler<MyEvent>, FeedPusher<MyEvent> {
 *     ...
 * }
 * }</pre>
 *
 * A handler that does not implement it does not support push: the framework then throws an
 * {@link UnsupportedOperationException} (which the admin endpoint translates into a 400).
 *
 * <p>Because {@code FeedPusher<C>} extends {@code FeedHandler<C>}, {@code C} is necessarily the handler's own
 * content type — a handler cannot declare a different push type (the compiler rejects two {@code FeedHandler}
 * type arguments). The framework decodes the pushed raw content with the
 * feed's regular decoder and hands it to {@link #pushEntry}. Unlike the regular processing callbacks there is
 * no {@link be.wegenenverkeer.atomium.client.protocol.AtomiumEntry} or
 * {@link be.wegenenverkeer.atomium.client.protocol.FeedPageMetadata} available (the item was never really on
 * the feed), so the handler only has to be able to process the content itself.
 *
 * <p><b>Threading:</b> a push runs on the calling (admin request) thread, <em>not</em> on the feed thread,
 * and can therefore coincide with a running run of the same handler. Since the handler is supposed to be
 * stateless anyway, that is not a problem — but do not count on push and the regular processing callbacks
 * never running concurrently.
 *
 * @param <C> the domain type of the entry content — the same type as the handler's
 */
public interface FeedPusher<C> extends FeedHandler<C> {

    /**
     * Process one pushed content item. Runs inside its own transaction, just like the normal processing —
     * but no feed pointer is advanced and no listener events are emitted (the item was not really on the
     * feed).
     */
    void pushEntry(C content);
}
