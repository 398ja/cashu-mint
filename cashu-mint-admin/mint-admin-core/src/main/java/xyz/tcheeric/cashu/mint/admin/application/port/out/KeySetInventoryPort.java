package xyz.tcheeric.cashu.mint.admin.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Outbound port for reading the keysets a mint holds in the shared vault.
 *
 * <p>The vault is the only source: the admin never asks the mint what it advertises
 * (ADR-0003). Disagreement between the two — keyset drift — is a separate concern.
 *
 * <p>Read failure and an empty result are different answers and implementations must
 * keep them apart: a mint with no keysets returns an empty list, an unreachable vault
 * throws. Collapsing the two would report a mint whose key material is intact as one
 * whose keysets are gone.
 */
public interface KeySetInventoryPort {

    /**
     * The keysets the vault holds for a mint, in whatever order the vault answers.
     *
     * @throws RuntimeException when the vault cannot be read
     */
    List<VaultKeySet> listByMint(UUID mintId);

    /**
     * One keyset as an operator needs to see it. Carries no key material: the vault stores
     * private keys, and this type has nowhere to put one.
     *
     * @param keySetId the keyset id wallets name
     * @param unit the unit the keyset serves
     * @param createdAt when the vault first stored it
     * @param archived whether the mint has retired it from signing; an archived keyset
     *                 still verifies and redeems indefinitely (ADR-0004)
     */
    record VaultKeySet(String keySetId, String unit, Instant createdAt, boolean archived) {
    }
}
