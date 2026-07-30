package be.wegenenverkeer.atomium.client.protocol;

import be.wegenenverkeer.atomium.client.exception.AtomiumInvalidPageException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * The metadata of a feed page: everything except the entries.
 * In particular contains the {@code links} used to navigate through the feed.
 */
public record FeedPageMetadata(
        String id,
        String base,
        String title,
        Generator generator,
        OffsetDateTime updated,
        List<Link> links
) {

    /**
     * The href of the link with the given {@code rel}, if present.
     */
    public Optional<String> optionalHref(FeedPageRel rel) {
        return links.stream()
                .filter(link -> link.rel().equals(rel.code()))
                .map(Link::href)
                .findFirst();
    }

    /**
     * The href of the link with the given {@code rel}.
     *
     * @throws AtomiumInvalidPageException if not present.
     */
    public String href(FeedPageRel rel) {
        return optionalHref(rel)
                .orElseThrow(() -> new AtomiumInvalidPageException("feed page without '%s' link: %s".formatted(rel, this)));
    }
}
