package xyz.tcheeric.cashu.mint.proto.ports;

import java.time.Instant;

/**
 * Domain-level view of a NUT-04 mint quote authorisation. Lives in the protocol
 * module so {@link xyz.tcheeric.cashu.mint.proto.tasks.MintTask} and friends can
 * reason about quote state without depending on Hibernate/JPA. The persistence
 * adapter ({@code cashu-mint-jpa}) implements this as a JPA entity.
 *
 * <p>All financial-amount fields are {@code long} per Constitution I / FR-009.
 *
 * <p>Spec: {@code specs/001-mint-quote-webhook-integrity/data-model.md} §
 * MintQuote.
 */
public interface MintQuote {

    /** Lifecycle states of a mint quote. See spec 001 § State Transition Invariants. */
    enum LifecycleState {
        /** Quote created; no payment observed. */
        UNPAID,
        /** Provider has acknowledged the payment intent (per protocol-level pending). */
        PENDING,
        /** Webhook confirmed payment with matching amount/unit/method/event id. */
        PAID,
        /** Mint task is signing blinded outputs; transient. */
        ISSUING,
        /** Signatures issued; terminal. */
        ISSUED,
        /** TTL elapsed before {@code ISSUED}. */
        EXPIRED,
        /** Operator-initiated triage state. */
        FAILED
    }

    String quoteId();

    long amount();

    String unit();

    String mintUrl();

    String paymentMethod();

    String invoiceId();

    LifecycleState lifecycleState();

    String requestHash();

    Instant createdAt();

    Instant updatedAt();
}
