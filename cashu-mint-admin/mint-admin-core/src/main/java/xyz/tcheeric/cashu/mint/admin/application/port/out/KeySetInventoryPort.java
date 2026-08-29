package xyz.tcheeric.cashu.mint.admin.application.port.out;

import java.math.BigInteger;
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
     * @param inputFeePpk NUT-02 fee charged per thousand inputs spent from this keyset,
     *                    zero when it charges nothing. Fixed for the life of the keyset:
     *                    a fee change is a rotation (ADR-0009), so an operator comparing
     *                    this against an archived keyset is reading the fee its proofs
     *                    are still redeemed at
     */
    record VaultKeySet(String keySetId, String unit, Instant createdAt, boolean archived,
                       int inputFeePpk) {
    }

    /**
     * The denominations one of a mint's keysets holds, ascending by amount.
     *
     * <p>Scoped to the mint on purpose: the keyset id alone would let one mint's URL address
     * another's keyset, and an Operator reading denominations off the wrong mint would draw
     * exactly the wrong conclusion about a rotation.
     *
     * <p>A keyset the mint does not hold is an empty list, on the same reasoning as
     * {@link #listByMint(UUID)}: absence is an answer, unreadability is a failure.
     *
     * @throws RuntimeException when the vault cannot be read
     */
    List<Denomination> listDenominations(UUID mintId, String keySetId);

    /**
     * One denomination and where its private key lives. The path is a locator, not the
     * secret: reading it requires HashiCorp Vault credentials the browser never holds.
     *
     * @param amount the denomination this key signs
     * @param vaultPath where the private key is stored, for backup and recovery
     */
    record Denomination(BigInteger amount, String vaultPath) {
    }
}
