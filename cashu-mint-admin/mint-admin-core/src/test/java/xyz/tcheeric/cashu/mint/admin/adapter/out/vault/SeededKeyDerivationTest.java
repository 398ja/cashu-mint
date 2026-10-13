package xyz.tcheeric.cashu.mint.admin.adapter.out.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import xyz.tcheeric.cashu.common.PrivateKey;
import xyz.tcheeric.cashu.crypto.util.KeySetIdV2Derivation;

/**
 * Keyset private keys must depend on a secret seed, not only on public identifiers (cashu-mint#545).
 *
 * <p>The previous derivation was {@code SHA-256(mintId|unit|amount[|rotationId])}. Every input is
 * public or leaks (the mint id is in logs, outbox payloads, admin responses and fixtures), so anyone
 * holding a mint id could recompute its private keys and sign ecash the mint accepts as genuine.
 */
class SeededKeyDerivationTest {

    private static final BigInteger SECP256K1_N = new BigInteger(
        "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16);
    private static final UUID MINT_ID = UUID.fromString("5f0a7a8e-2c1d-4b8e-9a41-0d3c6f1e7b22");
    private static final String UNIT = "sat";
    private static final List<Integer> LADDER = List.of(
        1, 2, 4, 8, 16, 32, 64, 128, 256, 512, 1024,
        2048, 4096, 8192, 16384, 32768, 65536, 131072, 262144, 524288,
        1048576, 2097152, 4194304, 8388608);

    private static KeysetMasterSeed randomSeed() {
        final byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return KeysetMasterSeed.fromHex(HexFormat.of().formatHex(bytes));
    }

    private static DeterministicKeyGenerator generator(final KeysetMasterSeed seed) {
        return new DeterministicKeyGenerator(() -> seed);
    }

    /** What an attacker can compute from public values alone: the pre-fix formula. */
    private static String unseeded(final UUID mintId, final String unit, final int amount, final String rotationId) {
        try {
            final String material = rotationId == null
                ? mintId + "|" + unit + "|" + amount
                : mintId + "|" + unit + "|" + amount + "|" + rotationId;
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Compares keys without ever putting their value in a failure message or a log. */
    private static void assertDiffer(final String a, final String b, final String what) {
        assertThat(a.equalsIgnoreCase(b)).as("%s must differ (values withheld)", what).isFalse();
    }

    @Test
    @DisplayName("forgery regression: no key equals the unseeded SHA-256 of the mint id, unit and amount")
    void keysCannotBeRederivedFromTheMintId() {
        final DeterministicKeyGenerator generator = generator(randomSeed());
        final String rotationId = UUID.randomUUID().toString();
        for (final int amount : LADDER) {
            assertDiffer(generator.derivePrivateKeyHex(MINT_ID, UNIT, amount, null),
                unseeded(MINT_ID, UNIT, amount, null), "provisioned key for " + amount);
            assertDiffer(generator.derivePrivateKeyHex(MINT_ID, UNIT, amount, rotationId),
                unseeded(MINT_ID, UNIT, amount, rotationId), "rotated key for " + amount);
        }
    }

    @Test
    @DisplayName("a keyset id computed from public values alone does not match the provisioned one")
    void keysetIdIsNotReproducibleWithoutTheSeed() {
        final String provisioned = generator(randomSeed()).deriveKeySetId(MINT_ID, UNIT, LADDER, null, 0);

        final Map<BigInteger, byte[]> guessed = new LinkedHashMap<>();
        for (final int amount : LADDER) {
            guessed.put(BigInteger.valueOf(amount), PrivateKey.derivePublicKey(
                PrivateKey.fromString(unseeded(MINT_ID, UNIT, amount, null))).getBytes());
        }
        assertThat(provisioned).isNotEqualTo(KeySetIdV2Derivation.getId(guessed, UNIT, 0, null));
    }

    @Test
    @DisplayName("a different seed gives different keys for the same public inputs")
    void keysDependOnTheSeed() {
        final DeterministicKeyGenerator a = generator(randomSeed());
        final DeterministicKeyGenerator b = generator(randomSeed());
        for (final int amount : LADDER) {
            assertDiffer(a.derivePrivateKeyHex(MINT_ID, UNIT, amount, null),
                b.derivePrivateKeyHex(MINT_ID, UNIT, amount, null), "key for " + amount);
        }
        assertThat(a.deriveKeySetId(MINT_ID, UNIT, LADDER, null, 0))
            .isNotEqualTo(b.deriveKeySetId(MINT_ID, UNIT, LADDER, null, 0));
    }

    @Test
    @DisplayName("idempotency: the same seed derives the same keys, so outbox redelivery is a no-op")
    void sameSeedSameKeys() {
        final KeysetMasterSeed seed = randomSeed();
        final String rotationId = "ctl-1";
        for (final int amount : LADDER) {
            final boolean same = generator(seed).derivePrivateKeyHex(MINT_ID, UNIT, amount, rotationId)
                .equals(generator(KeysetMasterSeed.fromHex(seed.revealHexForTestOnly()))
                    .derivePrivateKeyHex(MINT_ID, UNIT, amount, rotationId));
            assertThat(same).as("key for %d must be stable under one seed", amount).isTrue();
        }
    }

    @Test
    @DisplayName("every derived key is a valid secp256k1 scalar in [1, n-1]")
    void keysAreValidScalars() {
        for (int i = 0; i < 20; i++) {
            final DeterministicKeyGenerator generator = generator(randomSeed());
            for (final int amount : LADDER) {
                final BigInteger k = new BigInteger(generator.derivePrivateKeyHex(MINT_ID, UNIT, amount, null), 16);
                assertThat(k.signum() > 0 && k.compareTo(SECP256K1_N) < 0)
                    .as("key for %d must be in [1, n-1]", amount).isTrue();
            }
        }
    }

    @Test
    @DisplayName("the fields are unambiguous: shifting text between unit and rotation id changes the key")
    void fieldsAreDomainSeparated() {
        final DeterministicKeyGenerator generator = generator(randomSeed());
        assertDiffer(generator.derivePrivateKeyHex(MINT_ID, "sat", 1, "|x"),
            generator.derivePrivateKeyHex(MINT_ID, "sat|", 1, "x"), "unit/rotation boundary");
        assertDiffer(generator.derivePrivateKeyHex(MINT_ID, "sat", 1, null),
            generator.derivePrivateKeyHex(MINT_ID, "sat", 1, ""), "null vs empty rotation");
    }

    @Test
    @DisplayName("keyset ids stay NUT-02 v2: 01 version byte, 33 bytes, and the hash of the advertised keys")
    void keysetIdFollowsNut02() {
        final DeterministicKeyGenerator generator = generator(randomSeed());
        final String id = generator.deriveKeySetId(MINT_ID, UNIT, LADDER, null, 100);
        assertThat(id).hasSize(66).startsWith("01").matches("[0-9a-f]{66}");

        // Recompute the id from the public keys alone, the way a wallet verifies it (NUT-02).
        final Map<BigInteger, byte[]> keys = new LinkedHashMap<>();
        for (final int amount : LADDER) {
            keys.put(BigInteger.valueOf(amount), PrivateKey.derivePublicKey(
                PrivateKey.fromString(generator.derivePrivateKeyHex(MINT_ID, UNIT, amount, null))).getBytes());
        }
        assertThat(KeySetIdV2Derivation.getId(keys, UNIT, 100, null)).isEqualTo(id);
    }

    @Test
    @DisplayName("the generator cannot be built without a seed, and fails closed when the seed is unavailable")
    void failsClosedWithoutASeed() {
        assertThatThrownBy(() -> new DeterministicKeyGenerator(null)).isInstanceOf(NullPointerException.class);
        final DeterministicKeyGenerator unavailable = new DeterministicKeyGenerator(() -> {
            throw new IllegalStateException("no seed in Vault");
        });
        assertThatThrownBy(() -> unavailable.derivePrivateKeyHex(MINT_ID, UNIT, 1, null))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("weak or malformed seeds are refused")
    void weakSeedsAreRefused() {
        final List<String> weak = new ArrayList<>(List.of(
            "",                                   // empty
            "00".repeat(32),                      // all zero
            "ff".repeat(32),                      // all one byte
            "0123456789abcdef".repeat(4),         // repeating pattern
            "ab".repeat(16),                      // too short (16 bytes)
            "zz".repeat(32),                      // not hex
            unseeded(MINT_ID, UNIT, 1, null) + "00", // wrong length
            "deadbeef".repeat(8)));               // low distinct byte count
        for (final String hex : weak) {
            assertThatThrownBy(() -> KeysetMasterSeed.fromHex(hex))
                .as("seed of length %d must be refused", hex.length())
                .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> KeysetMasterSeed.fromHex(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the seed never appears in toString or exception messages")
    void seedIsNeverPrinted() {
        final KeysetMasterSeed seed = randomSeed();
        final String hex = seed.revealHexForTestOnly();
        assertThat(seed.toString()).doesNotContain(hex).doesNotContain(hex.substring(0, 16));
        assertThat(seed.fingerprint()).hasSize(16).doesNotContain(hex.substring(0, 16));

        final String almost = "00" + hex.substring(2, 62) + "0";
        assertThatThrownBy(() -> KeysetMasterSeed.fromHex(almost))
            .satisfies(e -> assertThat(e.getMessage()).doesNotContain(almost));
    }
}
