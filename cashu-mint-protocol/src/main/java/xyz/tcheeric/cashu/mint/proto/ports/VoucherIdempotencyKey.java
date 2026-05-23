package xyz.tcheeric.cashu.mint.proto.ports;

import java.time.Instant;

/**
 * Spec 003 — durable Idempotency-Key cache for voucher endpoints
 * (research R10). Stored in the {@code voucher_idempotency_key} table
 * so a JVM restart does not lose the cache (FR-009 mandates the
 * "same response on retry" guarantee outlast a restart).
 */
public interface VoucherIdempotencyKey {

    String idempotencyKey();

    String principalId();

    String requestHash();

    int responseStatus();

    String responseBodyJson();

    Instant expiresAt();

    Instant createdAt();
}
