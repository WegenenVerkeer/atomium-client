package be.wegenenverkeer.atomium.client.handler;

/**
 * Process a raw content item (JSON) <em>as if</em> it had been an entry on the feed: decode it and offer it
 * to the handler (via {@link FeedPusher#pushEntry}).
 * This small abstraction hides the {@link FeedContentDecoder}/{@link FeedHandler} from management tooling
 * (e.g. an admin endpoint).
 * Implemented by {@link FeedConsumerImpl} (which owns the decoder + handler + transaction).
 */
@FunctionalInterface
public interface EntryPusher {

    /**
     * @param rawContent the raw (JSON) content of the item
     * @throws RuntimeException if the content cannot be decoded or the handler fails
     */
    void pushEntry(String rawContent);
}
