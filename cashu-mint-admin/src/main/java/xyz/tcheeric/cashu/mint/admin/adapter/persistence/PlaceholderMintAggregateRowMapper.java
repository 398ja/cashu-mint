package xyz.tcheeric.cashu.mint.admin.adapter.persistence;

import static java.util.Objects.requireNonNull;

import java.util.Optional;

import xyz.tcheeric.cashu.mint.admin.domain.MintAggregate;

/**
 * Placeholder mapper that extracts lightweight relational columns and defers reconstruction.
 */
public final class PlaceholderMintAggregateRowMapper implements MintAggregateRowMapper {

    @Override
    public MintAggregateRow toRow(final MintAggregate aggregate) {
        requireNonNull(aggregate, "mint aggregate must not be null");
        return new MintAggregateRow(
            aggregate.mintId().asString(),
            aggregate.lifecycleState().value().name(),
            aggregate.configurationSet().revisionId().toString(),
            aggregate.operatorAccount().operatorId().toString(),
            aggregate.notificationPolicy().auditMetadata().action(),
            aggregate.auditMetadata().timestamp()
        );
    }

    @Override
    public Optional<MintAggregate> toAggregate(final MintAggregateRow row) {
        requireNonNull(row, "mint aggregate row must not be null");
        return Optional.empty();
    }
}
