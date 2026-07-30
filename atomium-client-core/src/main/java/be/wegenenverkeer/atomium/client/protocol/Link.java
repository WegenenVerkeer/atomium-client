package be.wegenenverkeer.atomium.client.protocol;

/**
 * A link with a {@code rel} (relation) and an {@code href} (relative url).
 *
 * <p>Used on a {@link FeedPage} and (optionally) on an {@link AtomiumEntry}.
 *
 * <p>The links on the {@link FeedPage} are essential to Atomium: they are used to navigate
 * between pages. See the helpers on {@link FeedPageMetadata} ({@code href(FeedPageRel)},
 * {@code optionalHref(FeedPageRel)}).
 */
public record Link(String rel, String href) {
}
