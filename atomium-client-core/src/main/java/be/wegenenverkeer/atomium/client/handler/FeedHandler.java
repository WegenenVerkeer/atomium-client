package be.wegenenverkeer.atomium.client.handler;

/**
 * The base SPI for consuming an Atomium feed. Do not implement this interface directly, but one of its
 * concrete variants; the framework sets up a feed consumer per handler:
 * <ul>
 *   <li>{@link EntryFeedHandler} — process the entries one by one; for self-contained events with local
 *       processing.</li>
 *   <li>{@link SimpleProcessingFeedHandler} — process them per batch, in two phases (prepare outside
 *       the transaction, persist inside it), for all processing that involves remote work — also
 *       single-entity events — and for feeds that produce bursts.</li>
 * </ul>
 *
 * <p>Only {@link #getFeedId()} (plus the entry callback of the chosen variant) is mandatory; everything else has a
 * sensible default. Support for <em>push</em> (processing an item via the admin endpoint as if it were on the
 * feed) is a separate opt-in capability: implement {@link FeedPusher} next to the handler variant. It is deliberately an interface (with default methods) and not an abstract class, so the lib
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
}
