package xyz.tcheeric.cashu.mint.admin.adapter.persistence;

import java.util.List;
import java.util.Optional;

import xyz.tcheeric.cashu.mint.admin.domain.MintAggregate;
import xyz.tcheeric.cashu.mint.admin.domain.MintId;

/**
 * Repository contract for persisting {@link MintAggregate} instances.
 */
public interface MintRepository {

    Optional<MintAggregate> findById(MintId mintId);

    List<MintAggregate> findAll();

    MintAggregate save(MintAggregate aggregate);

    void delete(MintId mintId);
}
