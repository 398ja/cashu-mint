package xyz.tcheeric.cashu.mint.proto.ports;

import java.time.Instant;

/**
 * Append-only record of a payment webhook the mint received, with the resolved
 * outcome. Keyed by {@code (provider, provider_event_id)} per FR-006.
 *
 * <p>Spec 001: data-model § WebhookEvent.
 */
public interface WebhookEvent {

    /** Stable provider identifier (research R6). */
    String provider();

    /** Provider's event id (Lightning payment hash, Stripe {@code evt_…}, etc.). */
    String providerEventId();

    String quoteId();

    long amount();

    String unit();

    String paymentMethod();

    /** SHA-256 over the signature header value, or {@code null} when no signature provided. */
    String signatureDigest();

    Outcome outcome();

    Instant receivedAt();

    /** Outcomes recorded per FR-008. Mirror of the {@code outcome} CHECK constraint. */
    enum Outcome {
        accepted,
        amount_mismatch,
        unit_mismatch,
        method_mismatch,
        duplicate,
        tamper,
        unsigned_rejected,
        signature_invalid,
        expired,
        noop,
        orphan
    }
}
