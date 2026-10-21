package be.wegenenverkeer.atomium.client.handler;

import java.time.Duration;
import java.util.function.Supplier;

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

    /**
     * The default {@link Feed#stopTimeout()} in seconds; {@link #STOP_TIMEOUT} is the same value as a
     * {@link Duration}.
     */
    public static final long STOP_TIMEOUT_SECONDS = 60;

    /**
     * The default {@link Feed#stopTimeout()}: {@value #STOP_TIMEOUT_SECONDS} seconds, how long
     * {@link FeedRuntime#whileStopped(Supplier)} waits for a run in flight to stop.
     */
    public static final Duration STOP_TIMEOUT = Duration.ofSeconds(STOP_TIMEOUT_SECONDS);

    private FeedDefaults() {
    }
}
