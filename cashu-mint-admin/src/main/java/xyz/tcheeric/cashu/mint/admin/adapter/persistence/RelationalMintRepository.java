package xyz.tcheeric.cashu.mint.admin.adapter.persistence;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Optional;

import xyz.tcheeric.cashu.mint.admin.domain.MintAggregate;
import xyz.tcheeric.cashu.mint.admin.domain.MintId;

/**
 * Stub relational repository prepared for future persistence integration.
 */
public final class RelationalMintRepository implements MintRepository {

    private final MintAggregateRowMapper rowMapper;
    private final TransactionExecutor transactionExecutor;

    public RelationalMintRepository(final MintAggregateRowMapper rowMapper,
                                    final TransactionExecutor transactionExecutor) {
        this.rowMapper = requireNonNull(rowMapper, "row mapper must not be null");
        this.transactionExecutor = requireNonNull(transactionExecutor, "transaction executor must not be null");
    }

    @Override
    public Optional<MintAggregate> findById(final MintId mintId) {
        requireNonNull(mintId, "mint identifier must not be null");
        return transactionExecutor.execute(() -> Optional.empty());
    }

    @Override
    public List<MintAggregate> findAll() {
        return transactionExecutor.execute(() -> List.<MintAggregate>of());
    }

    @Override
    public MintAggregate save(final MintAggregate aggregate) {
        requireNonNull(aggregate, "mint aggregate must not be null");
        return transactionExecutor.execute(() -> {
            rowMapper.toRow(aggregate);
            return aggregate;
        });
    }

    @Override
    public void delete(final MintId mintId) {
        requireNonNull(mintId, "mint identifier must not be null");
        transactionExecutor.executeVoid(() -> { });
    }
}
