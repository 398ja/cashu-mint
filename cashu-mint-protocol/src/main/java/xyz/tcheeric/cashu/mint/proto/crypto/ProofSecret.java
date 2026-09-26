package xyz.tcheeric.cashu.mint.proto.crypto;

import lombok.NonNull;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.vault.db.log.SecretLogId;

/**
 * A proof's raw secret, not yet hashed to a {@link StorageKey}.
 *
 * <p>The spent-proof store is keyed on {@code Y = hash_to_curve(secret)}, so a lookup by secret
 * hashes its input first. Handing it a {@code Y} instead hashes the point a second time and misses
 * every row, and a miss reads as {@code UNSPENT}. Giving the secret its own type makes that a
 * compile error rather than a silent answer (cashu-mint#487).
 *
 * <p>{@link #toString()} is redacted: a secret is what spends the proof, and this type is passed to
 * code that logs its arguments.
 *
 * @param value the secret exactly as the proof carries it
 */
public record ProofSecret(@NonNull String value) {

    /** The secret a proof carries, in the string form NUT-00 hashes. */
    public static ProofSecret of(@NonNull Secret secret) {
        return new ProofSecret(secret.toString());
    }

    @Override
    public String toString() {
        return SecretLogId.of(value);
    }
}
