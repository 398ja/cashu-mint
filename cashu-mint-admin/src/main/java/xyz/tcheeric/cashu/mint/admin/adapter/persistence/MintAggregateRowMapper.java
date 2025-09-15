package xyz.tcheeric.cashu.mint.admin.adapter.persistence;

import java.util.Optional;

import xyz.tcheeric.cashu.mint.admin.domain.MintAggregate;

/**
 * Maps {@link MintAggregate} instances to and from {@link MintAggregateRow} representations.
 */
public interface MintAggregateRowMapper {

    MintAggregateRow toRow(MintAggregate aggregate);

    Optional<MintAggregate> toAggregate(MintAggregateRow row);
}
