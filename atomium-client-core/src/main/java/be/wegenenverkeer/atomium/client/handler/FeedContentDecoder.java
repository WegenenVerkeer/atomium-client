package be.wegenenverkeer.atomium.client.handler;

/**
 * Decodes the raw entry content (as it appears on the feed, typically JSON) into the content type {@code T}
 * of the {@link FeedHandler}. The framework invokes this per entry before the handler callback; a decode failure
 * may simply throw a {@link RuntimeException} — the run then fails without advancing the feed pointer,
 * so that the entry is delivered again on the next run.
 */
@FunctionalInterface
public interface FeedContentDecoder<T> {

    T readFeedContent(String rawContent);
}
