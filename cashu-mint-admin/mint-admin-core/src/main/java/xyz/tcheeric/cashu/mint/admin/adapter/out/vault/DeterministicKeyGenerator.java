package xyz.tcheeric.cashu.mint.admin.adapter.out.vault;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

import xyz.tcheeric.cashu.common.Keys;
import xyz.tcheeric.cashu.common.PrivateKey;
import xyz.tcheeric.cashu.crypto.util.KeySetIdV2Derivation;

/**
 * Derives keyset key material for mint-admin provisioning and rotation.
 *
 * <h2>Why the derivation is keyed (cashu-mint#545)</h2>
 *
 * <p>Keys were {@code SHA-256(mintId|unit|amount[|rotationId])}. Every input there is public or
 * leaks: the unit and denominations are on {@code /v1/keys}, the rotation id is an operational
 * control id, and the mint id is in logs, outbox payloads, admin responses and fixtures. Anyone
 * holding a mint id could therefore recompute that mint's private keys and sign ecash it would
 * accept as genuine, and confirm a guess for free against {@code /v1/keys}.
 *
 * <p>Keys are now {@code HMAC-SHA256(seed, context)}, where the seed is the 32-byte
 * {@link KeysetMasterSeed} held only in Vault. Without the seed the output is indistinguishable
 * from random, so no public value determines a key.
 *
 * <h2>Why still deterministic</h2>
 *
 * <p>Provisioning runs from an outbox and is redelivered on failure. Deriving the same keys again
 * for the same {@code (mint, unit, amount, rotation)} is what lets a redelivery land on the keyset it
 * already wrote instead of minting a second one; {@code VaultProvisioningAdapter} additionally reads
 * before it writes and refuses to overwrite a differing key (cashu-mint#544). Only the secret is new.
 *
 * <h2>Encoding</h2>
 *
 * <p>The context is a domain tag followed by length-prefixed fields, so no two distinct inputs share
 * an encoding (the old {@code |}-joined string let {@code unit="sat|"} collide with a rotation id
 * starting {@code "|"}). A null rotation id and an empty one are distinct. The digest is used as a
 * scalar only if it lies in {@code [1, n-1]}; otherwise a counter is incremented and it is
 * recomputed, so every key is valid without biasing the distribution.
 */
public class DeterministicKeyGenerator {

    /** secp256k1 group order. */
    static final BigInteger SECP256K1_N = new BigInteger(
        "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16);

    private static final byte[] DOMAIN = "cashu-mint-admin/keyset-key/v1".getBytes(StandardCharsets.UTF_8);

    private final Supplier<KeysetMasterSeed> seed;

    /**
     * @param seed supplies the master seed; it must throw rather than return null when the seed is
     *             unavailable, so derivation fails closed
     */
    public DeterministicKeyGenerator(final Supplier<KeysetMasterSeed> seed) {
        this.seed = Objects.requireNonNull(seed, "keyset master seed supplier must not be null");
    }

    /** The private key for a mint's original keyset. */
    public String derivePrivateKeyHex(final UUID mintId, final String unit, final int amount) {
        return derivePrivateKeyHex(mintId, unit, amount, null);
    }

    /**
     * The private key for a denomination of a keyset.
     *
     * <p>{@code rotationId} is the operational control id of the rotation that produced the keyset,
     * or null for the mint's original keyset. Keying on it rather than on a counter is what makes
     * rotation idempotent: a redelivered outbox message derives the same keyset.
     */
    public String derivePrivateKeyHex(final UUID mintId, final String unit, final int amount,
                                      final String rotationId) {
        Objects.requireNonNull(mintId, "mint id must not be null");
        Objects.requireNonNull(unit, "unit must not be null");
        final KeysetMasterSeed masterSeed = Objects.requireNonNull(seed.get(),
            "keyset master seed is unavailable; refusing to derive keys");
        final byte[] context = context(mintId, unit, amount, rotationId);
        for (int counter = 0; counter < 256; counter++) {
            final byte[] digest = masterSeed.hmac(withCounter(context, counter));
            final BigInteger k = new BigInteger(1, digest);
            if (k.signum() > 0 && k.compareTo(SECP256K1_N) < 0) {
                return HexFormat.of().formatHex(digest);
            }
        }
        // Probability about 2^-32768: unreachable unless HMAC is broken.
        throw new IllegalStateException("could not derive a valid secp256k1 scalar");
    }

    /** Computes the NUT-02 v2 keyset id of a mint's original keyset at no fee. */
    public String deriveKeySetId(final UUID mintId, final String unit, final List<Integer> denominations) {
        return deriveKeySetId(mintId, unit, denominations, null);
    }

    /** Computes the NUT-02 v2 keyset id of a rotation at no fee. */
    public String deriveKeySetId(final UUID mintId, final String unit, final List<Integer> denominations,
                                 final String rotationId) {
        return deriveKeySetId(mintId, unit, denominations, rotationId, 0);
    }

    /**
     * Derives the NUT-02 v2 id of a keyset, which commits to its public keys, unit and fee.
     *
     * <p>The id is computed from the PUBLIC keys exactly as a wallet verifies it, so the id stays
     * NUT-02 conformant; it reveals nothing about the seed.
     */
    public String deriveKeySetId(final UUID mintId, final String unit, final List<Integer> denominations,
                                 final String rotationId, final int inputFeePpk) {
        final Keys keys = new Keys();
        for (final int amount : denominations) {
            final String hex = derivePrivateKeyHex(mintId, unit, amount, rotationId);
            final PrivateKey pk = PrivateKey.fromString(hex);
            keys.put(BigInteger.valueOf(amount), PrivateKey.derivePublicKey(pk));
        }
        return KeySetIdV2Derivation.getId(keys.values(), unit, inputFeePpk, null);
    }

    /**
     * A deterministic row id from a composite key using {@link UUID#nameUUIDFromBytes(byte[])}.
     *
     * <p>Row ids are identifiers, not secrets, so they are deliberately not keyed: they must be
     * stable across seeds so an existing row is found again.
     */
    public UUID deterministicId(final UUID mintId, final String unit, final String value) {
        final String source = mintId + "|" + unit + "|" + value;
        return UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] context(final UUID mintId, final String unit, final int amount,
                                  final String rotationId) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        field(out, DOMAIN);
        field(out, mintId.toString().getBytes(StandardCharsets.UTF_8));
        field(out, unit.getBytes(StandardCharsets.UTF_8));
        field(out, ByteBuffer.allocate(Integer.BYTES).putInt(amount).array());
        if (rotationId == null) {
            out.write(0);
        } else {
            out.write(1);
            field(out, rotationId.getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }

    private static void field(final ByteArrayOutputStream out, final byte[] value) {
        out.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
        out.writeBytes(value);
    }

    private static byte[] withCounter(final byte[] context, final int counter) {
        final byte[] message = new byte[context.length + 1];
        System.arraycopy(context, 0, message, 0, context.length);
        message[context.length] = (byte) counter;
        return message;
    }
}
