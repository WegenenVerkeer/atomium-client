package be.wegenenverkeer.atomium.client.handler;

import be.wegenenverkeer.atomium.client.protocol.AtomiumEntry;
import be.wegenenverkeer.atomium.client.protocol.FeedPageMetadata;

/**
 * The typical {@link FeedHandler}: process the feed entries <em>one by one</em>, in read order. For each entry the
 * framework commits the effect of {@link #onEntry} together with the advanced feedPointer in one transaction, so
 * that on a crash no processed event is lost and no committed event is offered twice.
 *
 * <p>The choice between the two variants hinges on <em>where the processing work happens</em>, not on how
 * many entities an event concerns. This is the variant for events whose payload is self-contained and whose
 * processing is local work (database writes). The moment processing involves <em>remote</em> work — the
 * common feed shape "entity X changed, fetch its latest version" — implement
 * {@link SimpleProcessingFeedHandler} instead, even though every event concerns a single entity: the
 * remote calls move outside the transaction, repeated ids within a burst collapse into one lookup, and
 * catching up a backlog goes per batch instead of per event.
 *
 * @param <C> the domain type of the entry content
 */
public interface EntryFeedHandler<C> extends FeedHandler<C> {

    /**
     * Is this entry relevant to this handler? {@code false} → the framework ignores it entirely (no
     * {@link #onEntry} callback) and simply advances the feed pointer over it.
     *
     * <p>Intended for feeds that carry many events this consumer has nothing to do with. Operates on the
     * <em>decoded</em> content (the decode happens per entry anyway). Default: everything is relevant.
     */
    default boolean accepts(FeedPageMetadata pageMetadata, AtomiumEntry entry, C content) {
        return true;
    }

    /**
     * Called for every feed entry, in read order (oldest first). All parameters are
     * guaranteed non-{@code null}.
     *
     * @param pageMetadata the metadata of the page the entry was on
     * @param entry        the raw entry (id, updated, links, raw content)
     * @param content      the deserialized content
     */
    void onEntry(FeedPageMetadata pageMetadata, AtomiumEntry entry, C content);
}
