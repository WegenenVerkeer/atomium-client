package be.wegenenverkeer.atomium.client.springboot;

/**
 * The kinds of start position of a feed: the {@code initial-feed-pointer.type} of a brand-new feed in the
 * configuration, and the {@code type} of a move through the admin endpoint.
 */
public enum FeedPointerType {
    /** From the oldest page: consume the full history. */
    OLDEST,
    /** Only events added from now on (skip the existing ones on the head). */
    NOW,
    /** From an explicit page link. */
    POINTER
}
