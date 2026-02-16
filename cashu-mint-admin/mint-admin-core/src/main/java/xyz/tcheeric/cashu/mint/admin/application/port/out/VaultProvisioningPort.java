package xyz.tcheeric.cashu.mint.admin.application.port.out;

import java.util.List;
import java.util.UUID;

/**
 * Outbound port for provisioning a mint's cryptographic material in the vault.
 */
public interface VaultProvisioningPort {

    /**
     * Provisions a mint entity, keyset, and key entries in the vault.
     * Implementations must be idempotent: if a resource already exists
     * (409 Conflict), it is treated as success.
     */
    void provision(UUID mintId, String unit, List<Integer> denominations);

    /**
     * Checks whether the vault already contains provisioned material for the mint.
     */
    boolean isProvisioned(UUID mintId);

    /**
     * Archives the vault keyset associated with the mint.
     */
    void archive(UUID mintId);

    /**
     * Compensates for a failed provisioning attempt by deleting any partial
     * vault state created during provisioning.
     */
    void compensate(UUID mintId);
}
