package xyz.tcheeric.cashu.mint.admin.rest.nap;

import nostr.crypto.bech32.Bech32;
import nostr.crypto.bech32.Bech32Prefix;

/**
 * Converts between the {@code npub} form Operators are given and the lower-case hex NAP reports.
 *
 * <p>Operators are handed an npub and nothing else, while every lookup compares on hex,
 * so the conversion has to happen somewhere; here, once, rather than at each caller.
 */
public final class Npubs {

    // The message reaches a 400 body, so it names the field rather than echoing what the
    // caller sent; the caller already knows what they typed.
    private static final String NOT_AN_NPUB = "not a valid npub";

    private Npubs() {
    }

    /**
     * @param npub a bech32 {@code npub1...} public key
     * @return the same key as 64 lower-case hex characters
     * @throws IllegalArgumentException when the value is not a decodable npub
     */
    public static String toPubkeyHex(final String npub) {
        // bech32 is defined as all-lower or all-upper, and the decoder only takes lower, so
        // an npub copied out of an upper-cased QR code is normalised rather than refused.
        final String normalised = npub == null ? null
            : npub.equals(npub.toUpperCase()) ? npub.toLowerCase() : npub;
        final String hex;
        try {
            // Bech32.fromBech32 declares `throws Exception`, so there is no narrower catch.
            hex = normalised != null && normalised.startsWith("npub1") ? Bech32.fromBech32(normalised) : null;
        } catch (final Exception ex) {
            throw new IllegalArgumentException(NOT_AN_NPUB, ex);
        }
        if (hex == null || hex.length() != 64) {
            throw new IllegalArgumentException(NOT_AN_NPUB);
        }
        return hex.toLowerCase();
    }

    /**
     * The lower-case hex key NAP reports, as the npub an Operator recognises.
     *
     * @param pubkeyHex 64 hex characters, or null
     * @return the {@code npub1...} form, or null when there is no key
     */
    public static String toNpub(final String pubkeyHex) {
        return pubkeyHex == null ? null : Bech32.toBech32(Bech32Prefix.NPUB, pubkeyHex);
    }
}
