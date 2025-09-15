package xyz.tcheeric.cashu.mint.admin.application.port.out;

import java.util.List;
import java.util.Optional;

import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationRevisionId;
import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationSet;
import xyz.tcheeric.cashu.mint.admin.domain.MintId;

/**
 * Persistence port for configuration set history.
 */
public interface ConfigurationSetRepository {

    /**
     * Persist a configuration revision for the supplied mint.
     *
     * @param mintId the mint identifier
     * @param configurationSet the configuration snapshot to store
     */
    void save(MintId mintId, ConfigurationSet configurationSet);

    /**
     * Retrieve a specific configuration revision.
     *
     * @param mintId the mint identifier
     * @param revisionId the revision identifier
     * @return the configuration if present
     */
    Optional<ConfigurationSet> findByRevision(MintId mintId, ConfigurationRevisionId revisionId);

    /**
     * Retrieve the ordered configuration history for the supplied mint.
     *
     * @param mintId the mint identifier
     * @return the list of configuration snapshots ordered by revision
     */
    List<ConfigurationSet> findByMintId(MintId mintId);
}
