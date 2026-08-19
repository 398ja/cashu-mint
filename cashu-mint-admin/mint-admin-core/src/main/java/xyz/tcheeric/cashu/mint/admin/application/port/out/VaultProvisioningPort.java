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
     * Rotates the mint's signing keyset for a unit: generates a new keyset and
     * archives the one it replaces.
     *
     * <p>Implementations must be idempotent on {@code rotationId} — a redelivered
     * message must resolve to the same new keyset rather than producing a second
     * one. Archiving only takes effect as a retirement because the mint refuses
     * to sign with an archived keyset; see ADR-0004.
     *
     * @param mintId mint whose keyset is rotating
     * @param unit unit the keyset serves
     * @param denominations denominations the new keyset must cover
     * @param rotationId operational control id driving this rotation
     * @return the new and previous keyset ids
     */
    RotationResult rotate(UUID mintId, String unit, List<Integer> denominations, String rotationId);

    /**
     * Outcome of a rotation, carried into the audit trail so an operator can
     * reconstruct which keyset replaced which.
     *
     * @param newKeySetId keyset now signing
     * @param previousKeySetIds keysets archived by this rotation
     */
    record RotationResult(String newKeySetId, List<String> previousKeySetIds) {
    }

    /**
     * Compensates for a failed provisioning attempt by deleting any partial
     * vault state created during provisioning.
     */
    void compensate(UUID mintId);
}
