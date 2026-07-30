package be.wegenenverkeer.atomium.client.handler;

/**
 * The {@link FeedHandler} for the <em>exceptional</em> case: a feed that produces events in bursts, faster than
 * you can process them one by one. Process such a feed per entry and you fall days behind.
 *
 * <p>The framework buffers the (accepted) entries in a {@link FeedHandlerBatch} and calls {@link #onBatch} as
 * soon as that batch is complete — or, in any case, at the end of the feed. The effect of the batch and the
 * advanced feed pointer are committed together in one transaction, so a crash at most repeats a not yet
 * committed batch.
 *
 * <p>The gain lies in two things: {@link #accepts} keeps the irrelevant events out, and the batch
 * <b>deduplicates</b> — see {@link DefaultFeedHandlerBatch}. If you do not override {@code startBatch}, the batch
 * dedups on the content's own {@code equals}; usually you want to supply a key from your domain:
 *
 * <pre>{@code
 * @Component
 * class CallsHandler implements BatchedFeedHandler<Call> {
 *
 *     @Override
 *     public String getFeedId() {
 *         return "callservice";
 *     }
 *
 *     @Override
 *     public FeedHandlerBatch<Call> startBatch(int preferredBatchSize) {
 *         return new DefaultFeedHandlerBatch<>(preferredBatchSize, Call::callId);
 *     }
 *
 *     @Override
 *     public void onBatch(FeedHandlerBatch<Call> batch) {
 *         for (BatchEntry<Call> e : batch.getBuffer()) {   // per call only the last state
 *             ...
 *         }
 *     }
 * }
 * }</pre>
 *
 * <p>The bean stays <em>stateless</em>: the mutable state lives in the (fresh per batch) {@link FeedHandlerBatch}.
 *
 * @param <C> the domain type of the entry content
 */
public interface BatchedFeedHandler<C> extends FeedHandler<C> {

    /**
     * Create a fresh, empty batch. The framework calls this at the start of a run and after every flush — never
     * concurrently. Here you choose your batch policy: the dedup key, or an entirely custom {@link FeedHandlerBatch}.
     *
     * <p>The <em>size</em> is handed to you by the framework, from the
     * {@code preferredBatchSize} of the {@link Feed} (default {@value FeedDefaults#PREFERRED_BATCH_SIZE}).
     * That is deliberate: the key is a domain concern and
     * belongs in code, the size is a tuning parameter that differs per environment. So pass it on to your batch —
     * or ignore it if your own {@link FeedHandlerBatch} has an entirely different policy (time-based, for example).
     *
     * @param preferredBatchSize the number of distinct keys at which the batch should be complete
     */
    default FeedHandlerBatch<C> startBatch(int preferredBatchSize) {
        return new DefaultFeedHandlerBatch<>(preferredBatchSize, content -> content);
    }

    /**
     * Process a complete batch. Runs inside the transaction that also advances the feed pointer: if you throw here,
     * everything rolls back and the batch is delivered again on the next run.
     *
     * <p>You get the {@link FeedHandlerBatch} itself (not just its entries), so that a custom batch implementation
     * can also hand over domain aggregates it built along the way. With the default you simply read
     * {@link FeedHandlerBatch#getBuffer()}.
     */
    void onBatch(FeedHandlerBatch<C> batch);
}
