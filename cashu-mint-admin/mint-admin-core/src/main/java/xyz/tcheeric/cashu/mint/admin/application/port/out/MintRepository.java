package xyz.tcheeric.cashu.mint.admin.application.port.out;

import java.util.List;
import java.util.Optional;

import xyz.tcheeric.cashu.mint.admin.domain.MintAggregate;
import xyz.tcheeric.cashu.mint.admin.domain.MintId;

/**
 * Persistence port for the mint aggregate.
 */
public interface MintRepository {

    /**
     * Persist or update the supplied aggregate and its child entities.
     *
     * @param aggregate the aggregate to persist
     */
    void save(MintAggregate aggregate);

    /**
     * Load a mint aggregate by its identifier.
     *
     * @param mintId the aggregate identifier
     * @return the aggregate if present
     */
    Optional<MintAggregate> findById(MintId mintId);

    /**
     * Load all known mint aggregates.
     *
     * @return an immutable list of aggregates
     */
    List<MintAggregate> findAll();

    /**
     * Check whether an {@code ACTIVE} mint already exists for the given unit,
     * excluding the specified mint from the check.
     *
     * @param unit          the currency unit (e.g. "sat", "usd")
     * @param excludeMintId the mint to exclude from the check
     * @return {@code true} if another active mint exists for that unit
     */
    boolean existsActiveByUnit(String unit, MintId excludeMintId);
}
