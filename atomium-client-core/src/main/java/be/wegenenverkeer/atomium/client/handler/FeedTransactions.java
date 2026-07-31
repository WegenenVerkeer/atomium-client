package be.wegenenverkeer.atomium.client.handler;

import java.util.function.Supplier;

/**
 * Executes the processing work of a feed in a transaction. The framework uses this in exactly two
 * places: the commit of processed work (the handler effect and the feed pointer atomically together) and a
 * push of a standalone content item.
 *
 * <p><b>Contract:</b> the work runs entirely within one transaction. If the work throws a
 * {@link RuntimeException}, the transaction is rolled back and the exception is rethrown unchanged.
 */
public interface FeedTransactions {

    /** Execute {@code work} in one transaction and return the result. */
    <T> T inTransaction(Supplier<T> work);

    /** Execute {@code work} without a result in one transaction. */
    default void inTransactionWithoutResult(Runnable work) {
        inTransaction(() -> {
            work.run();
            return null;
        });
    }

    /**
     * An implementation without transactions: the work is executed directly. For applications without
     * transactional persistence. The guarantee that the handler effect and the feed pointer are committed together
     * is then lost: after a crash during a commit the feed pointer can lag behind (or run ahead of) the
     * handler's effect — which makes it all the more important that the processing is idempotent.
     */
    static FeedTransactions withoutTransactions() {
        return new FeedTransactions() {
            @Override
            public <T> T inTransaction(Supplier<T> work) {
                return work.get();
            }
        };
    }
}
