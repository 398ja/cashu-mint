package xyz.tcheeric.cashu.mint.admin.adapter.out.vault;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class DeterministicKeyGeneratorTest {

    private static final UUID MINT_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    private static final String UNIT = "sat";
    private static final List<Integer> DENOMINATIONS = List.of(1, 2, 4, 8, 16, 32, 64, 128);

    private final DeterministicKeyGenerator generator = new DeterministicKeyGenerator();

    @Test
    // Ensures the same input always produces the same private key hex.
    void shouldProduceDeterministicPrivateKey() {
        final String key1 = generator.derivePrivateKeyHex(MINT_ID, UNIT, 1);
        final String key2 = generator.derivePrivateKeyHex(MINT_ID, UNIT, 1);

        assertThat(key1).isEqualTo(key2);
        assertThat(key1).hasSize(64);
        assertThat(key1).matches("[0-9a-f]{64}");
    }

    @Test
    // Ensures different amounts produce different private keys.
    void shouldProduceDifferentKeysForDifferentAmounts() {
        final String key1 = generator.derivePrivateKeyHex(MINT_ID, UNIT, 1);
        final String key2 = generator.derivePrivateKeyHex(MINT_ID, UNIT, 2);

        assertThat(key1).isNotEqualTo(key2);
    }

    @Test
    // Ensures keyset ID derivation produces a non-empty, deterministic result.
    void shouldDeriveConsistentKeySetId() {
        final String id1 = generator.deriveKeySetId(MINT_ID, UNIT, DENOMINATIONS);
        final String id2 = generator.deriveKeySetId(MINT_ID, UNIT, DENOMINATIONS);

        assertThat(id1).isEqualTo(id2);
        assertThat(id1).isNotBlank();
    }

    @Test
    // Ensures deterministic UUID generation is consistent across calls.
    void shouldGenerateDeterministicUuid() {
        final UUID id1 = generator.deterministicId(MINT_ID, UNIT, "test");
        final UUID id2 = generator.deterministicId(MINT_ID, UNIT, "test");

        assertThat(id1).isEqualTo(id2);
    }

    @Test
    // Ensures different inputs produce different deterministic UUIDs.
    void shouldGenerateDifferentUuidsForDifferentInputs() {
        final UUID id1 = generator.deterministicId(MINT_ID, UNIT, "1");
        final UUID id2 = generator.deterministicId(MINT_ID, UNIT, "2");

        assertThat(id1).isNotEqualTo(id2);
    }

    /**
     * The fee is part of the NUT-02 v2 identity, so two otherwise identical keysets that charge
     * differently must not share an id.
     *
     * <p>VaultProvisioningAdapter derived the id without passing the fee, so it always used 0 and
     * then stored the real fee alongside. A mint charging 100 ppk therefore advertised the same id
     * as one charging nothing, and a wallet keying off the id could not see the change. That is
     * precisely the guarantee v2 exists to give.
     */
    @Test
    void keySetIdCommitsToTheInputFee() {
        final String free = generator.deriveKeySetId(MINT_ID, UNIT, DENOMINATIONS, null, 0);
        final String charged = generator.deriveKeySetId(MINT_ID, UNIT, DENOMINATIONS, null, 100);

        assertThat(free).isNotEqualTo(charged);
    }

    /** And the same fee must still derive the same id, or provisioning would not be idempotent. */
    @Test
    void sameFeeDerivesTheSameId() {
        assertThat(generator.deriveKeySetId(MINT_ID, UNIT, DENOMINATIONS, null, 100))
                .isEqualTo(generator.deriveKeySetId(MINT_ID, UNIT, DENOMINATIONS, null, 100));
    }

    /** A provisioned id must be NUT-02 v2: 66 hex chars beginning with the 01 version byte. */
    @Test
    void derivesAVersion2KeysetId() {
        final String id = generator.deriveKeySetId(MINT_ID, UNIT, DENOMINATIONS, null, 100);

        assertThat(id).hasSize(66).startsWith("01");
    }

}
