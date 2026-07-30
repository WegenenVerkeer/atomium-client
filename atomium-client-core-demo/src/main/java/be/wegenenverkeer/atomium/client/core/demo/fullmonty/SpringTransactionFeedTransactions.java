package be.wegenenverkeer.atomium.client.core.demo.fullmonty;

import be.wegenenverkeer.atomium.client.handler.FeedTransactions;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/**
 * A real implementation of the {@link FeedTransactions} building block, here on Spring's
 * {@link TransactionTemplate}: the handler effect and the feed pointer commit together in one transaction, and a
 * {@link RuntimeException} rolls back and is rethrown (that is the contract). A stack without Spring writes the
 * same thin layer around its own transaction mechanism.
 */
class SpringTransactionFeedTransactions implements FeedTransactions {

    private final TransactionTemplate transactionTemplate;

    SpringTransactionFeedTransactions(TransactionTemplate transactionTemplate) {
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public <T> T inTransaction(Supplier<T> work) {
        // execute() is @Nullable, but only returns null when the callback does: the nullness of the
        // result is therefore exactly that of work.get(). (No requireNonNull: inTransactionWithoutResult
        // legitimately yields null.)
        return transactionTemplate.execute(status -> work.get());
    }
}
