package xyz.tcheeric.cashu.mint.proto.crypto;

import lombok.NonNull;
import xyz.tcheeric.cashu.common.PublicKey;
import xyz.tcheeric.cashu.crypto.BDHKEUtils;
import xyz.tcheeric.cashu.crypto.SecretEncoding;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The key under which the spent-proof store records a proof: {@code Y = hash_to_curve(secret)}.
 *
 * <p>NUT-00 hashes the UTF-8 bytes of the secret string, but this mint previously hex-decoded a
 * plain secret before hashing it. The same secret therefore has two possible curve points, and a
 * proof spent before the correction is recorded under the legacy one. A double-spend check that
 * only ever computed the spec point would find no row for that proof and report it unspent, which
 * is a double-spend hole rather than a compatibility inconvenience.
 *
 * <p>This class is the single place that answers the two questions the store needs:
 * {@link #lookupKeys(ProofSecret)} for "under which keys might this proof already be recorded" and
 * {@link #issuanceKey(ProofSecret)} for "under which key does the mint record a proof it has
 * issued". It is also the only place a {@link ProofSecret} becomes a {@link StorageKey}.
 *
 * @see SecretEncoding
 * @see <a href="https://github.com/cashubtc/nuts/blob/main/00.md">NUT-00</a>
 */
public final class SpentProofKey {

    private SpentProofKey() {
    }

    /**
     * Every key a proof with this secret could already be recorded under, in the order
     * {@link SecretEncoding#verificationOrder()} defines: the spec key first, the legacy key only
     * as a fallback for proofs issued before the encoding was corrected.
     *
     * <p>Encodings that cannot be applied to this secret are skipped, so the legacy key never
     * widens a lookup beyond the two points the proof could genuinely have been issued under.
     */
    public static List<StorageKey> lookupKeys(@NonNull ProofSecret secret) {
        List<StorageKey> keys = new ArrayList<>(SecretEncoding.verificationOrder().size());
        for (SecretEncoding encoding : SecretEncoding.verificationOrder()) {
            keyUnder(secret.value(), encoding)
                    .filter(key -> !keys.contains(key))
                    .ifPresent(keys::add);
        }
        return List.copyOf(keys);
    }

    /**
     * The key a proof is recorded under when no earlier record of it exists. Issuance is never
     * ambiguous, so this is always the spec encoding.
     */
    public static StorageKey issuanceKey(@NonNull ProofSecret secret) {
        return keyUnder(secret.value(), SecretEncoding.forIssuance())
                .orElseThrow(() -> new IllegalArgumentException(
                        "secret cannot be encoded under " + SecretEncoding.forIssuance()));
    }

    private static Optional<StorageKey> keyUnder(String secret, SecretEncoding encoding) {
        if (secret.isEmpty() || !encoding.supports(secret)) {
            return Optional.empty();
        }
        byte[] point;
        try {
            point = BDHKEUtils.hashToCurve(secret, encoding);
        } catch (IllegalArgumentException inapplicable) {
            return Optional.empty();
        }
        return Optional.of(StorageKey.of(PublicKey.fromBytes(point).toString()));
    }
}
