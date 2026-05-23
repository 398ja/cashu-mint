package xyz.tcheeric.cashu.mint.proto.ports;

import java.time.Instant;
import java.util.Optional;

/**
 * Spec 003 — port to the {@code voucher_idempotency_key} table. The
 * filter middleware writes through {@link #save} on first observation
 * and reads via {@link #findByKey} on retry. {@link #deleteExpiredBefore}
 * backs the scheduled TTL sweep.
 */
public interface VoucherIdempotencyKeyRepository {

    Optional<VoucherIdempotencyKey> findByKey(String idempotencyKey, String principalId);

    VoucherIdempotencyKey save(VoucherIdempotencyKey row);

    /** Deletes rows whose {@code expires_at} is earlier than the given instant. */
    int deleteExpiredBefore(Instant cutoff);
}
