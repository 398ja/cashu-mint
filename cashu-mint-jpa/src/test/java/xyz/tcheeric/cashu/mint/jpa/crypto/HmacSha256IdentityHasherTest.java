package xyz.tcheeric.cashu.mint.jpa.crypto;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.mint.proto.ports.IdentityHasher;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Spec 004 T023 — unit tests for {@link HmacSha256IdentityHasher}
 * covering every row in {@code contracts/identity-hasher.md} §
 * Required behaviour table.
 */
class HmacSha256IdentityHasherTest {

    private static final String SAMPLE_SALT_HEX_64 =
            "9f8a2c1b7e4d6a3f0c5b9d8e7f6a4c2b1d8e9f7a3c5b2d4e6f1a8c0b9d7e5f3a";
    // A second salt with same length, used to prove salt sensitivity.
    private static final String SAMPLE_SALT_HEX_64_DIFFERENT =
            "1111111111111111111111111111111111111111111111111111111111111111";

    private IdentityHasher hasher;

    @BeforeEach
    void setUp() {
        hasher = newHasher(SAMPLE_SALT_HEX_64);
    }

    @Test
    void hashesShortCircuitsOnNull() {
        // FR-019: anonymous purchase — null in, null out, no Mac invocation.
        assertThat(hasher.hash(null)).isNull();
    }

    @Test
    void hashesShortCircuitsOnEmpty() {
        assertThat(hasher.hash("")).isNull();
    }

    @Test
    void hashesShortCircuitsOnWhitespace() {
        assertThat(hasher.hash("   ")).isNull();
        assertThat(hasher.hash("\t\n")).isNull();
    }

    @Test
    void hashesAreDeterministic() {
        // Operator forensic CLI relies on the same input always producing
        // the same hash so the lookup matches.
        String value = "npub1abc...definitely-not-real-key";
        String first = hasher.hash(value);
        for (int i = 0; i < 1000; i++) {
            assertThat(hasher.hash(value)).isEqualTo(first);
        }
    }

    @Test
    void hashesAre64CharHex() {
        String hash = hasher.hash("npub1abc...");
        assertThat(hash).hasSize(64);
        assertThat(hash).matches("^[0-9a-f]{64}$");
    }

    @Test
    void differentInputsProduceDifferentHashes() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String hash = hasher.hash("npub-" + UUID.randomUUID());
            assertThat(seen.add(hash))
                    .as("hash collision at iteration %d", i)
                    .isTrue();
        }
    }

    @Test
    void differentSaltsProduceDifferentHashesForSameInput() {
        IdentityHasher second = newHasher(SAMPLE_SALT_HEX_64_DIFFERENT);
        assertThat(hasher.hash("npub-shared"))
                .isNotEqualTo(second.hash("npub-shared"));
    }

    @Test
    void bootFailsWhenSaltUnset() {
        assertThatThrownBy(() -> newHasher(""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cashu.mint.voucher.identity-salt is required");
    }

    @Test
    void bootFailsWhenSaltTooShort() {
        // 31 chars = 31 bytes, below the 32-byte minimum
        assertThatThrownBy(() -> newHasher("a".repeat(31)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 bytes of entropy");
    }

    private static IdentityHasher newHasher(String salt) {
        HmacSha256IdentityHasher h = new HmacSha256IdentityHasher(salt);
        h.validateSalt();
        return h;
    }
}
