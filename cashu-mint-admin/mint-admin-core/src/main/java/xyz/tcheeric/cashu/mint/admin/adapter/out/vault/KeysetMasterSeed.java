package xyz.tcheeric.cashu.mint.admin.adapter.out.vault;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The secret every keyset private key mint-admin provisions is derived from (cashu-mint#545).
 *
 * <p>Keys used to be {@code SHA-256(mintId|unit|amount)}: a function of public identifiers only, so
 * anyone who knew a mint id could recompute its private keys and forge ecash. The seed is what makes
 * the derivation secret. It is 32 bytes from a CSPRNG, lives only in Vault, and is read by mint-admin
 * at startup. It must never be written to the database, an env file, a log or an outbox payload.
 *
 * <p>Construction refuses anything that is not plausibly random: the wrong length, non-hex, or too
 * few distinct bytes (all-zero, a repeated byte, a short repeated pattern). A uniformly random
 * 32-byte value has about 30 distinct bytes; fewer than 16 has negligible probability by chance
 * and is what a placeholder, a test constant or a typed-in value looks like.
 *
 * <p>The value never appears in {@link #toString()} or in an exception message. {@link #fingerprint()}
 * is a one-way tag operators can compare across hosts to confirm which seed is loaded.
 */
public final class KeysetMasterSeed {

    /** Seed length in bytes. */
    public static final int LENGTH = 32;

    /** Fewest distinct byte values a seed may contain. */
    static final int MIN_DISTINCT_BYTES = 16;

    private static final String HMAC = "HmacSHA256";

    private final byte[] bytes;

    private KeysetMasterSeed(final byte[] bytes) {
        this.bytes = bytes;
    }

    /**
     * Parses and validates a seed.
     *
     * @param hex 64 hex characters
     * @throws IllegalArgumentException if the value is missing, malformed or weak; the message never
     *                                  contains the value
     */
    public static KeysetMasterSeed fromHex(final String hex) {
        if (hex == null || hex.isBlank()) {
            throw new IllegalArgumentException("Keyset master seed is missing");
        }
        final String normalised = hex.strip().toLowerCase(Locale.ROOT);
        if (normalised.length() != LENGTH * 2 || !normalised.matches("[0-9a-f]+")) {
            throw new IllegalArgumentException("Keyset master seed must be " + LENGTH * 2
                + " hex characters (" + LENGTH + " random bytes); got " + normalised.length()
                + " characters. Generate one with: openssl rand -hex 32");
        }
        final byte[] parsed = HexFormat.of().parseHex(normalised);
        final long distinct = distinctBytes(parsed);
        if (distinct < MIN_DISTINCT_BYTES) {
            Arrays.fill(parsed, (byte) 0);
            throw new IllegalArgumentException("Keyset master seed is weak: " + distinct
                + " distinct byte values where a random seed has about 30. It looks like a "
                + "placeholder or a pattern, not CSPRNG output. Generate one with: openssl rand -hex 32");
        }
        return new KeysetMasterSeed(parsed);
    }

    private static long distinctBytes(final byte[] value) {
        final boolean[] seen = new boolean[256];
        long count = 0;
        for (final byte b : value) {
            if (!seen[b & 0xff]) {
                seen[b & 0xff] = true;
                count++;
            }
        }
        return count;
    }

    /** HMAC-SHA256 keyed with the seed. */
    byte[] hmac(final byte[] message) {
        try {
            final Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(bytes, HMAC));
            return mac.doFinal(message);
        } catch (final NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    /**
     * A one-way, non-secret tag for this seed: the first 8 bytes of
     * {@code HMAC(seed, "cashu-mint-admin/keyset-seed-fingerprint")}, in hex.
     */
    public String fingerprint() {
        final byte[] tag = hmac("cashu-mint-admin/keyset-seed-fingerprint".getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(tag, 0, 8);
    }

    /** Test seam only: the tests prove two parses of one value derive identical keys. */
    String revealHexForTestOnly() {
        return HexFormat.of().formatHex(bytes);
    }

    @Override
    public String toString() {
        return "KeysetMasterSeed[fingerprint=" + fingerprint() + "]";
    }
}
