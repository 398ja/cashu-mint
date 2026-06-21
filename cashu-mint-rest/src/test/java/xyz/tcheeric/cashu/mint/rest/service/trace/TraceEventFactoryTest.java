package xyz.tcheeric.cashu.mint.rest.service.trace;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.ledger.trace.core.OperationInvariants;
import xyz.tcheeric.cashu.ledger.trace.core.OperationKind;
import xyz.tcheeric.cashu.ledger.trace.core.TransactionEvent;
import xyz.tcheeric.cashu.ledger.trace.publisher.InMemoryOperationIdRegistry;
import java.util.List;
import xyz.tcheeric.cashu.ledger.trace.publisher.TraceEventSigner;
import xyz.tcheeric.cashu.mint.rest.event.TraceMeltFailedEvent;
import xyz.tcheeric.cashu.mint.rest.event.TraceMeltQuoteRequestedEvent;
import xyz.tcheeric.cashu.mint.rest.event.TraceMintFailedEvent;
import xyz.tcheeric.cashu.mint.rest.event.TraceMintQuoteRequestedEvent;
import xyz.tcheeric.cashu.mint.rest.event.TraceProofInput;

/**
 * Spec 036 US1 — verifies the factory produces structurally valid
 * MINT_QUOTE_REQUESTED / MELT_QUOTE_REQUESTED events that pass the ledger's
 * per-kind invariants before any relay is involved.
 */
class TraceEventFactoryTest {

    private static final String MINT_URL = "https://mint.test";
    // Valid secp256k1 private key (= 1) for deriving a producer pubkey in tests.
    private static final String TEST_PRIVATE_KEY =
            "0000000000000000000000000000000000000000000000000000000000000001";

    private TraceEventFactory factory() {
        return new TraceEventFactory(MINT_URL, new InMemoryOperationIdRegistry(),
                new TraceEventSigner(TEST_PRIVATE_KEY));
    }

    // A fresh mint-quote event maps to a valid MINT_QUOTE_REQUESTED: 0 in/0 out,
    // lightning present, no invariant violations.
    @Test
    void mintQuoteRequested_isValidAndShaped() {
        TraceMintQuoteRequestedEvent in = new TraceMintQuoteRequestedEvent(
                this, "quote-1", "lnbc100n1...", null, 100L, "sat",
                (int) Instant.parse("2026-06-21T00:00:00Z").getEpochSecond(), Instant.now());

        TransactionEvent ev = factory().buildMintQuoteRequested(in);

        assertThat(OperationInvariants.validate(ev)).isEmpty();
        assertThat(ev.kind()).isEqualTo(OperationKind.MINT_QUOTE_REQUESTED);
        assertThat(ev.inputs()).isEmpty();
        assertThat(ev.outputs()).isEmpty();
        assertThat(ev.unit()).isEqualTo("sat");
        assertThat(ev.mintUrl()).isEqualTo(MINT_URL);
        assertThat(ev.producerPubkey()).isNotBlank(); // signer's key, validated at sign
        assertThat(ev.initiatorPubkey()).isEmpty(); // Principle VII
        assertThat(ev.lightning()).isPresent();
        assertThat(ev.lightning().get().quoteId()).isEqualTo("quote-1");
        assertThat(ev.lightning().get().amount()).contains(100L);
        assertThat(ev.lightning().get().quoteOperation()).isEqualTo(OperationKind.MINT_QUOTE_REQUESTED);
    }

    // A fresh melt-quote event maps to a valid MELT_QUOTE_REQUESTED carrying the
    // fee reserve as feeAmount.
    @Test
    void meltQuoteRequested_isValidAndCarriesFeeReserve() {
        TraceMeltQuoteRequestedEvent in = new TraceMeltQuoteRequestedEvent(
                this, "quote-2", "lnbc200n1...", 200L, 5L, null, 0, Instant.now());

        TransactionEvent ev = factory().buildMeltQuoteRequested(in);

        assertThat(OperationInvariants.validate(ev)).isEmpty();
        assertThat(ev.kind()).isEqualTo(OperationKind.MELT_QUOTE_REQUESTED);
        assertThat(ev.inputs()).isEmpty();
        assertThat(ev.outputs()).isEmpty();
        assertThat(ev.unit()).isEqualTo("sat"); // null source unit defaults
        assertThat(ev.feeAmount()).contains(5L);
        assertThat(ev.lightning()).isPresent();
        assertThat(ev.lightning().get().quoteOperation()).isEqualTo(OperationKind.MELT_QUOTE_REQUESTED);
    }

    // MINT_FAILED is valid with no proofs (corrected invariant) and an error code.
    @Test
    void mintFailed_isValidWithNoProofs() {
        TraceMintFailedEvent in = new TraceMintFailedEvent(
                this, "quote-4", 100L, "sat", "mint_invoice_not_paid_error",
                "invoice not paid", Instant.now());

        TransactionEvent ev = factory().buildMintFailed(in);

        assertThat(OperationInvariants.validate(ev)).isEmpty();
        assertThat(ev.kind()).isEqualTo(OperationKind.MINT_FAILED);
        assertThat(ev.inputs()).isEmpty();
        assertThat(ev.outputs()).isEmpty();
        assertThat(ev.errorCode()).contains("mint_invoice_not_paid_error");
    }

    // MELT_FAILED is valid with >=1 input (Y only, no secret) and 0 outputs.
    @Test
    void meltFailed_isValidWithInputYsAndNoSecret() {
        String y = "02" + "a".repeat(64); // 66-char lowercase hex compressed point
        TraceMeltFailedEvent in = new TraceMeltFailedEvent(
                this, "quote-5", 8L, "sat",
                List.of(new TraceProofInput(8L, "00ad268c4d1f5826", y)),
                "melt_invoice_not_paid_error", "payment failed", Instant.now());

        TransactionEvent ev = factory().buildMeltFailed(in);

        assertThat(OperationInvariants.validate(ev)).isEmpty();
        assertThat(ev.kind()).isEqualTo(OperationKind.MELT_FAILED);
        assertThat(ev.inputs()).hasSize(1);
        assertThat(ev.outputs()).isEmpty();
        assertThat(ev.inputs().get(0).y()).isEqualTo(y);
        assertThat(ev.inputs().get(0).secret()).isEmpty(); // Principle VII
        assertThat(ev.errorCode()).contains("melt_invoice_not_paid_error");
    }

    // The same quoteId resolves to the same operationId (idempotency anchor, FR-008).
    @Test
    void sameQuoteId_yieldsSameOperationId() {
        TraceEventFactory f = factory();
        TraceMintQuoteRequestedEvent in = new TraceMintQuoteRequestedEvent(
                this, "quote-3", "lnbc1...", null, 10L, "sat", 0, Instant.now());

        String id1 = f.buildMintQuoteRequested(in).operationId();
        String id2 = f.buildMintQuoteRequested(in).operationId();

        assertThat(id1).isEqualTo(id2);
    }
}
