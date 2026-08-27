package xyz.tcheeric.cashu.mint.admin.rest.nap;

import nostr.crypto.bech32.Bech32;

/**
 * Converts the {@code npub} form Operators are given into the lower-case hex NAP reports.
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
        final String hex;
        try {
            hex = npub != null && npub.startsWith("npub1") ? Bech32.fromBech32(npub) : null;
        } catch (final Exception ex) {
            throw new IllegalArgumentException(NOT_AN_NPUB, ex);
        }
        if (hex == null || hex.length() != 64) {
            throw new IllegalArgumentException(NOT_AN_NPUB);
        }
        return hex.toLowerCase();
    }
}
