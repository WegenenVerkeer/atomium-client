package be.wegenenverkeer.atomium.client.handler;

import be.wegenenverkeer.atomium.client.protocol.AtomiumEntry;
import be.wegenenverkeer.atomium.client.protocol.FeedPageMetadata;

/**
 * The typical {@link FeedHandler}: process the feed entries <em>one by one</em>, in read order. For each entry the
 * framework commits the effect of {@link #onEntry} together with the advanced feedPointer in one transaction, so
 * that on a crash no processed event is lost and no committed event is offered twice.
 *
 * <p>This is the variant you want, unless your feed produces events in bursts faster than you can process them
 * one by one.
 *
 * @param <C> the domain type of the entry content
 */
public interface EntryFeedHandler<C> extends FeedHandler<C> {

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
