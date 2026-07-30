package be.wegenenverkeer.atomium.client.handler;

/**
 * The defaults of the handler API, in one place. Documentation refers to them with {@code {@value}}, so the
 * documented values can never drift from the real ones. Exception: markdown has no
 * {@code {@value}} — if you change a value here, also update the config table in
 * {@code atomium-client-spring-boot-4/README.md}.
 */
public final class FeedDefaults {

    /** The batch size of a {@link BatchedFeedHandler} without an explicit {@link Feed#preferredBatchSize()}. */
    public static final int PREFERRED_BATCH_SIZE = 100;

    /** The safety net {@link Feed#maxUnflushedPages()}: force a flush after this many pages without a flush. */
    public static final int MAX_UNFLUSHED_PAGES = 10;

    private FeedDefaults() {
    }
}
