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

        /**
         * A real amount that disagrees with the quote's.
         *
         * <p>Money may have moved for the wrong figure, so this is a
         * reconciliation question rather than a client bug.
         */
        amount_mismatch,

        /**
         * No usable amount at all: null, zero or negative (#469).
         *
         * <p>Distinct from {@link #amount_mismatch}, which was previously
         * recorded for both. Nothing could have been charged for a non-positive
         * amount, so the fault is in whatever raised the invoice rather than in
         * a disagreement about its size.
         *
         * <p>The conflation had a measured cost: nine zero-amount invoices on
         * 2026-09-23 showed as 9962 {@code amount_mismatch} events, and the
         * first diagnosis read that as a unit or scale disagreement between the
         * adapter and the mint. The log line had said {@code invalid_amount}
         * all along; the metric had not.
         */
        invalid_amount,
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
