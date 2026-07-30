package be.wegenenverkeer.atomium.client.handler;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Test {@link FeedTransactions}: runs the work directly (no real transactions) and counts the commits and
 * rollbacks, so a test can assert that the failure path really went through the transaction layer as a rollback.
 * In line with the contract, a {@link RuntimeException} from the work is rethrown unchanged.
 *
 * <p>Additionally, {@link #isInTransaction()} is the window on the <em>atomicity</em>: a handler or repository
 * that reads this flag during its callback thereby proves that it ran inside (or outside) the transaction scope.
 */
class RecordingFeedTransactions implements FeedTransactions {

    private final AtomicInteger commits = new AtomicInteger();
    private final AtomicInteger rollbacks = new AtomicInteger();
    private volatile boolean inTransaction;

    @Override
    public <T> T inTransaction(Supplier<T> work) {
        inTransaction = true;
        try {
            T result = work.get();
            commits.incrementAndGet();
            return result;
        } catch (RuntimeException e) {
            rollbacks.incrementAndGet();
            throw e;
        } finally {
            inTransaction = false;
        }
    }

    /** Is the calling code currently running inside an {@link #inTransaction(Supplier)} scope? */
    boolean isInTransaction() {
        return inTransaction;
    }

    int commits() {
        return commits.get();
    }

    int rollbacks() {
        return rollbacks.get();
    }
}
