package xyz.tcheeric.cashu.mint.admin.adapter.out.vault;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.UUID;

import xyz.tcheeric.cashu.common.Keys;
import xyz.tcheeric.cashu.common.PrivateKey;
import xyz.tcheeric.cashu.crypto.util.KeySetDerivation;

/**
 * Derives cryptographic key material deterministically from a mint identity,
 * unit, and set of denominations. The algorithm matches the one used by
 * {@code MintPreloadDataGenerator} in the tools module.
 */
public class DeterministicKeyGenerator {

    /**
     * Derives a private key hex string for the given mint, unit and denomination
     * using {@code SHA-256(mintId|unit|amount)}.
     */
    public String derivePrivateKeyHex(final UUID mintId, final String unit, final int amount) {
        final String material = mintId + "|" + unit + "|" + amount;
        final byte[] hash = sha256(material);
        return bytesToHex(hash);
    }

    /**
     * Computes the external keyset identifier by deriving public keys for every
     * denomination and passing them through the standard keyset ID algorithm.
     */
    public String deriveKeySetId(final UUID mintId, final String unit, final List<Integer> denominations) {
        final Keys keys = new Keys();
        for (final int amount : denominations) {
            final String hex = derivePrivateKeyHex(mintId, unit, amount);
            final PrivateKey pk = PrivateKey.fromString(hex);
            keys.put(BigInteger.valueOf(amount), PrivateKey.derivePublicKey(pk));
        }
        return KeySetDerivation.getId(keys.values());
    }

    /**
     * Generates a deterministic UUID from a composite key using
     * {@link UUID#nameUUIDFromBytes(byte[])}.
     */
    public UUID deterministicId(final UUID mintId, final String unit, final String value) {
        final String source = mintId + "|" + unit + "|" + value;
        return UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] sha256(final String input) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(input.getBytes(StandardCharsets.UTF_8));
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String bytesToHex(final byte[] bytes) {
        final StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (final byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
