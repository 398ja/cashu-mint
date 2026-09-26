package xyz.tcheeric.cashu.mint.proto.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.common.HashToCurveSecret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A storage key is a compressed curve point, refused where it is built if it is not one, so a
 * wrong value fails loudly instead of silently missing every stored proof (cashu-mint#487).
 */
@DisplayName("StorageKey")
class StorageKeyTest {

    private static final String Y = "02599b9ea0a1ad4143706c2a5a4a568ce442dd4313e1cf1f7f0b58a317c1a355ee";

    // A compressed point with either parity prefix is a valid key.
    @Test
    void acceptsACompressedPointWithEitherPrefix() {
        assertThat(StorageKey.of(Y).hex()).isEqualTo(Y);
        assertThat(StorageKey.of("03" + Y.substring(2)).hex()).startsWith("03");
    }

    // The store keys proofs on lowercase hex, so an uppercase Y must find the same row.
    @Test
    void normalisesHexToLowercase() {
        assertThat(StorageKey.of(Y.toUpperCase())).isEqualTo(StorageKey.of(Y));
    }

    // A raw secret is the mistake the type exists to stop: a 64-character hex secret looks like a
    // point to a quick glance but is not one, and must not become a key.
    @Test
    void refusesARawSecret() {
        String rawSecret = "a5c0e0a5e9e3d2c1b0a9f8e7d6c5b4a3928170615043f2e1d0c9b8a796857463";

        assertThatThrownBy(() -> StorageKey.of(rawSecret)).isInstanceOf(IllegalArgumentException.class);
    }

    // Anything that is not 66 hex characters starting 02 or 03 is refused.
    @Test
    void refusesMalformedValues() {
        assertThatThrownBy(() -> StorageKey.of("04" + Y.substring(2))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StorageKey.of(Y + "00")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StorageKey.of(Y.substring(0, 65) + "g")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StorageKey.of("proof-y-1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StorageKey(null)).isInstanceOf(IllegalArgumentException.class);
    }

    // A NUT-07 Y arrives already parsed as a point and converts without loss.
    @Test
    void convertsANut07Y() {
        assertThat(StorageKey.of(HashToCurveSecret.fromString(Y)).hex()).isEqualTo(Y);
    }

    // The key derived from a secret is itself a valid key, so the two halves of the API meet.
    @Test
    void theIssuanceKeyOfASecretIsAValidKey() {
        ProofSecret secret = new ProofSecret("a5c0e0a5e9e3d2c1b0a9f8e7d6c5b4a3928170615043f2e1d0c9b8a796857463");

        assertThat(SpentProofKey.issuanceKey(secret).hex()).matches("0[23][0-9a-f]{64}");
    }

    // A proof secret never prints itself: it is what spends the proof, and it travels through
    // code that logs its arguments.
    @Test
    void aProofSecretIsRedactedWhenPrinted() {
        String raw = "a5c0e0a5e9e3d2c1b0a9f8e7d6c5b4a3928170615043f2e1d0c9b8a796857463";

        assertThat(new ProofSecret(raw).toString()).doesNotContain(raw).startsWith("sha256:");
    }

    // A proof secret cannot be null: there is nothing to hash.
    @Test
    void aProofSecretIsNeverNull() {
        assertThatThrownBy(() -> new ProofSecret(null)).isInstanceOf(NullPointerException.class);
    }
}
