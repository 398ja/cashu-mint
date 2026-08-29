package xyz.tcheeric.cashu.mint.proto.tasks.validator;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.util.encoders.Hex;
import xyz.tcheeric.cashu.crypto.Schnorr;
import xyz.tcheeric.cashu.crypto.util.Utils;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Counts how many of a pathway's public keys have signed a message.
 *
 * <p>NUT-11 thresholds count <b>distinct public keys</b> with at least one valid signature, not
 * matching (key, signature) pairs. Schnorr signatures are non-deterministic, so counting raw
 * signatures would let a single key satisfy an n-of-m threshold by submitting the same signature
 * twice, or a crafted secret could repeat a pubkey. Each key therefore contributes at most one.
 */
@Slf4j
final class SigningKeyCounter {

    /** Length in hex characters of a 33-byte compressed public key. */
    private static final int COMPRESSED_HEX_LENGTH = 66;

    private SigningKeyCounter() {
    }

    /**
     * The number of distinct keys in {@code publicKeys} with a valid signature over {@code message}.
     */
    static int countSigningKeys(List<String> publicKeys, List<String> signatures, byte[] message) {
        if (publicKeys == null || signatures == null) {
            return 0;
        }
        byte[] hash = hashOrNull(message);
        if (hash == null) {
            return 0;
        }
        Set<String> countedKeys = new HashSet<>();
        int signingKeyCount = 0;
        for (String publicKey : publicKeys) {
            if (publicKey == null || publicKey.isBlank()) {
                continue;
            }
            String xOnly = xCoordinate(publicKey);
            if (!countedKeys.add(xOnly)) {
                continue;
            }
            if (hasValidSignature(xOnly, signatures, hash)) {
                signingKeyCount++;
            }
        }
        return signingKeyCount;
    }

    private static boolean hasValidSignature(String xOnlyKey, List<String> signatures, byte[] hash) {
        for (String signature : signatures) {
            try {
                // BIP-340 verifies against the 32-byte x-only key. NUT-11 carries the 33-byte
                // compressed form, which Schnorr.verify rejects outright, so the parity prefix is
                // stripped first — otherwise every spec-conformant key throws into the catch below
                // and silently counts as "no valid signature".
                if (Schnorr.verify(hash, Hex.decode(xOnlyKey), Hex.decode(signature))) {
                    return true;
                }
            } catch (Exception e) {
                log.warn("Error verifying signature. Continuing...", e);
            }
        }
        return false;
    }

    private static byte[] hashOrNull(@NonNull byte[] message) {
        try {
            return Utils.sha256(message);
        } catch (Exception e) {
            log.warn("Error hashing spend data. Rejecting signatures.", e);
            return null;
        }
    }

    /** The lowercase x-coordinate of a public key (strips a 33-byte compressed {@code 02/03} prefix). */
    private static String xCoordinate(String publicKeyHex) {
        String hex = publicKeyHex.toLowerCase();
        if (hex.length() == COMPRESSED_HEX_LENGTH && (hex.startsWith("02") || hex.startsWith("03"))) {
            return hex.substring(2);
        }
        return hex;
    }
}
