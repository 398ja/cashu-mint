package xyz.tcheeric.cashu.mint.proto.crypto;

import lombok.NonNull;
import xyz.tcheeric.cashu.common.HashToCurveSecret;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The key the spent-proof store records a proof under: the compressed hash-to-curve point
 * {@code Y = hash_to_curve(secret)}, as 66 lowercase hex characters.
 *
 * <p>A distinct type because a {@code Y} and a raw secret are both strings, and the lookup that
 * takes one hashes its input while the lookup that takes the other does not. Passing the wrong
 * one compiled, raised nothing and returned no proof, which every caller reads as
 * {@code UNSPENT}. That happened three times (NUT-07 checkstate, NUT-17 proof-state
 * subscriptions, the 409 recovery in proof invalidation) before the two were given separate types
 * (cashu-mint#487). See {@link ProofSecret} for the other half.
 *
 * <p>A malformed key is refused where it is built, so it fails there rather than as a silent miss
 * further down. Hex is lowercased because the store holds keys in the form {@code PublicKey}
 * prints them, so a client sending uppercase would otherwise miss a proof that is there.
 *
 * @param hex the compressed point, lowercase hex
 */
public record StorageKey(String hex) {

    private static final Pattern COMPRESSED_POINT = Pattern.compile("0[23][0-9a-f]{64}");

    public StorageKey {
        if (hex == null) {
            throw new IllegalArgumentException("A storage key is a curve point, not null");
        }
        hex = hex.toLowerCase(Locale.ROOT);
        if (!COMPRESSED_POINT.matcher(hex).matches()) {
            throw new IllegalArgumentException(
                    "A storage key is a compressed curve point (66 hex characters starting 02 or 03)");
        }
    }

    /**
     * The key a client supplied as {@code Y}, for example in NUT-17 {@code proof_state} filters.
     * Client input can be null; it is refused with the same {@link IllegalArgumentException} as
     * any other value that is not a point, so callers handle one failure, not two.
     */
    public static StorageKey of(String hex) {
        return new StorageKey(hex);
    }

    /** The key for a NUT-07 {@code Y}, which the request has already parsed as a point. */
    public static StorageKey of(@NonNull HashToCurveSecret y) {
        return new StorageKey(y.toString());
    }

    @Override
    public String toString() {
        return hex;
    }
}
