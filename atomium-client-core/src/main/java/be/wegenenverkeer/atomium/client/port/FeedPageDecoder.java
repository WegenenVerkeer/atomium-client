package be.wegenenverkeer.atomium.client.port;

import be.wegenenverkeer.atomium.client.exception.AtomiumInvalidPageException;
import be.wegenenverkeer.atomium.client.protocol.FeedPage;

/**
 * Port that decodes the JSON envelope of a feed page into a {@link FeedPage}.
 *
 * <p>The application supplies the implementation
 * (e.g. the {@code JacksonFeedPageDecoder} from the atomium-client-jackson-3 library, if the application
 * uses Jackson).</p>
 *
 * <p>The implementation decodes <strong>only the envelope</strong>: id, links, and per entry id/updated/
 * links plus the content.
 * The {@code content.value} remains a <strong>raw JSON String</strong> — it
 * is not decoded into a domain type.
 * The intention is that the feed consumer decodes the {@code content.value} while handling the event
 * (with a parser suited for domain objects) and handles any failures.</p>
 */
@FunctionalInterface
public interface FeedPageDecoder {

    /**
     * @throws AtomiumInvalidPageException on invalid JSON.
     */
    FeedPage readFeedPage(String json);
}
