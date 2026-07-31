package be.wegenenverkeer.atomium.client.handler;

import org.jspecify.annotations.Nullable;

/**
 * The result of {@link SimpleBatchedProcessingFeedHandler#process}: the prepared intermediate state {@code P}
 * that the framework carries into {@link SimpleBatchedProcessingFeedHandler#persist}, plus optionally the
 * batch's contribution to the {@code processed} counter.
 *
 * <p>{@code processed} is a <em>free measure of realised work</em> — the handler chooses its meaning. Unlike
 * {@code read} and {@code accepted} (which count feed entries) it may count something else entirely, such as
 * the business entities the batch upserts — and may therefore be smaller <em>or larger</em> than the number
 * of offered entries (dedup inside the content shrinks it; events carrying multiple entities grow it). Left
 * empty it defaults to the number of offered entries; {@link #of(Object)} therefore suffices for a handler
 * without a more meaningful measure.
 *
 * @param value     the prepared intermediate state, handed to {@code persist} unchanged
 * @param processed the batch's contribution to the processed counter, or {@code null} for the default (the
 *                  number of offered entries)
 * @param <P>       the type of the prepared intermediate state
 */
public record ProcessResult<P>(P value, @Nullable Integer processed) {

    public ProcessResult {
        if (processed != null && processed < 0) {
            throw new IllegalArgumentException("processed must not be negative, was " + processed);
        }
    }

    /** A result with the default {@code processed} count: the number of offered entries. */
    public static <P> ProcessResult<P> of(P value) {
        return new ProcessResult<>(value, null);
    }

    /** A result with an explicit {@code processed} count (e.g. entities upserted, or entries left after dedup). */
    public static <P> ProcessResult<P> of(P value, int processed) {
        return new ProcessResult<>(value, processed);
    }
}
