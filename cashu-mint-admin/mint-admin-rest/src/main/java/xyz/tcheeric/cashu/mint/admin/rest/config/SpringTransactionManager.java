package xyz.tcheeric.cashu.mint.admin.rest.config;

import java.util.Objects;
import java.util.function.Supplier;

import org.springframework.transaction.support.TransactionTemplate;

import xyz.tcheeric.cashu.mint.admin.application.port.out.TransactionManager;

/**
 * Adapts Spring's {@link TransactionTemplate} to the core {@link TransactionManager} port.
 */
public class SpringTransactionManager implements TransactionManager {

    private final TransactionTemplate transactionTemplate;

    public SpringTransactionManager(final TransactionTemplate transactionTemplate) {
        this.transactionTemplate = Objects.requireNonNull(transactionTemplate, "transaction template must not be null");
    }

    @Override
    public <T> T execute(final Supplier<T> action) {
        Objects.requireNonNull(action, "transactional action must not be null");
        return transactionTemplate.execute(status -> action.get());
    }
}
