package xyz.tcheeric.cashu.mint.proto.util;

import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.KeysetId;
import xyz.tcheeric.cashu.common.PublicKey;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Stable fingerprint over the blinded-output list of a NUT-04 mint request.
 *
 * <p>Spec 001 research R4: the hash is computed over the sorted tuples
 * {@code (amount, keyset_id, B_)} so the same set of outputs always produces
 * the same hash regardless of submission order. The resulting 32-byte
 * SHA-256 digest is stored in {@code issuance_record.outputs_hash} and is
 * compared on NUT-19 idempotent replay.
 *
 * <p>Sort order: amount ascending, then keyset id string ascending, then
 * blinded-message public key bytes ascending. Empty / null inputs are rejected
 * to keep the contract tight: callers should validate non-emptiness before
 * hashing.
 */
public final class OutputsHash {

    private static final Comparator<BlindedMessage> CANONICAL_ORDER =
            Comparator
                    .comparingInt(BlindedMessage::getAmount)
                    .thenComparing(m -> keysetIdString(m.getKeySetId()))
                    .thenComparing(m -> publicKeyHex(m.getBlindedMessage()));

    private OutputsHash() {
    }

    /**
     * Computes the canonical SHA-256 hash over the given outputs.
     *
     * @param outputs the blinded messages from the mint request
     * @return hex-encoded SHA-256 digest (64 characters)
     */
    public static String compute(List<BlindedMessage> outputs) {
        if (outputs == null || outputs.isEmpty()) {
            throw new IllegalArgumentException("outputs must not be empty");
        }

        MessageDigest digest = sha256();
        outputs.stream()
                .sorted(CANONICAL_ORDER)
                .forEach(m -> {
                    digest.update(Integer.toString(m.getAmount()).getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) ':');
                    digest.update(keysetIdString(m.getKeySetId()).getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) ':');
                    digest.update(publicKeyHex(m.getBlindedMessage()).getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) '\n');
                });
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String keysetIdString(KeysetId id) {
        Objects.requireNonNull(id, "keysetId must not be null");
        return id.toString();
    }

    private static String publicKeyHex(PublicKey key) {
        Objects.requireNonNull(key, "blindedMessage public key must not be null");
        return key.toString();
    }
}
