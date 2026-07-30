package be.wegenenverkeer.atomium.client.springboot;

import be.wegenenverkeer.atomium.client.handler.FeedTransactions;

import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/**
 * {@link FeedTransactions} on Spring's {@link TransactionTemplate}: every piece of work runs in one transaction
 * according to the template (default: a new transaction on the application's {@code PlatformTransactionManager}),
 * with the standard rollback-on-{@code RuntimeException} semantics.
 */
final class TransactionTemplateFeedTransactions implements FeedTransactions {

    private final TransactionTemplate transactionTemplate;

    TransactionTemplateFeedTransactions(TransactionTemplate transactionTemplate) {
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public <T> T inTransaction(Supplier<T> work) {
        // execute() is @Nullable, but only returns null when the callback does: the nullness of the
        // result is thus exactly that of work.get(). (No requireNonNull: inTransactionWithoutResult
        // legitimately yields null.)
        return transactionTemplate.execute(status -> work.get());
    }
}
