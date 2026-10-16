package be.wegenenverkeer.atomium.client.handler;

/**
 * The defaults of the handler API, in one place. Documentation refers to them with {@code {@value}}, so the
 * documented values can never drift from the real ones. Exception: markdown has no
 * {@code {@value}} — if you change a value here, also update the config table in
 * {@code atomium-client-spring-boot-4/README.md}.
 */
public final class FeedDefaults {

    /**
     * The <em>maximum</em> batch size (in accepted entries) of a {@link SimpleProcessingFeedHandler} without an explicit
     * {@link Feed#maxProcessingSize()}: one entry per batch, the only size that is safe for every handler. A handler
     * whose {@code process} works locally or in bulk wants a far larger size; that is a per-feed choice.
     */
    public static final int MAX_PROCESSING_SIZE = 1;

    /** The safety net {@link Feed#maxUncommittedPages()}: ask for a wrap-up after this many pages without a commit. */
    public static final int MAX_UNCOMMITTED_PAGES = 10;

    private FeedDefaults() {
    }
}
