package be.wegenenverkeer.atomium.client.handler;

/**
 * Internal wrapper that enriches a failure during the processing of a single entry with entry context (entry id +
 * phase), so that the {@link FeedRunner} can include it in the {@code runFailed} event and the ERROR log. The consumer
 * wraps decode/handler failures in this and lets them propagate; the runner unwraps them. Failures <em>without</em>
 * entry context (e.g. the source unreachable while fetching a page) are not wrapped.
 */
class FeedEntryProcessingException extends RuntimeException {

    private final String entryId;
    private final FeedEntryPhase phase;
    private final RuntimeException cause;

    FeedEntryProcessingException(String feedId, String entryId, FeedEntryPhase phase, RuntimeException cause) {
        super("feed '%s': %s failure at entry '%s'".formatted(feedId, phase, entryId), cause);
        this.entryId = entryId;
        this.phase = phase;
        this.cause = cause;
    }

    String entryId() {
        return entryId;
    }

    FeedEntryPhase phase() {
        return phase;
    }

    /** The underlying failure (the decode or handler exception itself). */
    RuntimeException cause() {
        return cause;
    }
}
