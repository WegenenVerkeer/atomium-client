package be.wegenenverkeer.atomium.client.handler;

/**
 * The phase in which entry processing went wrong: <em>decoding</em> the raw JSON into the content type,
 * or invoking the <em>handler</em> ({@code onEntry}). Support wants to know which of the two, to determine
 * whether the cause lies with the source data (decode) or with their own processing (handler).
 */
public enum FeedEntryPhase {
    DECODE,
    HANDLER
}
