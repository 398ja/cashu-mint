package xyz.tcheeric.cashu.mint.rest.service.trace;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import xyz.tcheeric.cashu.ledger.trace.core.LightningRef;
import xyz.tcheeric.cashu.ledger.trace.core.NostrEventMetadata;
import xyz.tcheeric.cashu.ledger.trace.core.OperationKind;
import xyz.tcheeric.cashu.ledger.trace.core.OutputRole;
import xyz.tcheeric.cashu.ledger.trace.core.PrivacyMode;
import xyz.tcheeric.cashu.ledger.trace.core.ProofRef;
import xyz.tcheeric.cashu.ledger.trace.core.TransactionEvent;
import xyz.tcheeric.cashu.ledger.trace.publisher.OperationIdRegistry;
import xyz.tcheeric.cashu.ledger.trace.publisher.TraceEventSigner;
import xyz.tcheeric.cashu.mint.rest.event.TraceMeltQuoteRequestedEvent;
import xyz.tcheeric.cashu.mint.rest.event.TraceMintQuoteRequestedEvent;

/**
 * Spec 036 — maps internal trace application events to the SDK's
 * {@link TransactionEvent} wire shape. Pure mapping; performs no I/O.
 *
 * <p>Mint-side rules (see {@code specs/036-.../contracts/trace-events.md}):
 * {@code producerPubkey} left blank (the SDK signer overwrites it),
 * {@code initiatorPubkey} omitted (Principle VII — no customer identity),
 * outputs always empty, and any input {@code ProofRef} carries the public
 * {@code Y} only — never the plaintext secret.
 *
 * <p>Only instantiated when {@code cashu.trace.publisher.enabled=true}; the
 * {@link OperationIdRegistry} it depends on is provided under the same gate.
 */
@Component
@ConditionalOnProperty(prefix = "cashu.trace.publisher", name = "enabled", havingValue = "true")
public class TraceEventFactory {

    /** Default unit emitted when the source operation does not pin one. */
    static final String DEFAULT_UNIT = "sat";

    private final String mintUrl;
    private final OperationIdRegistry operationIdRegistry;
    /** The producer's own public key; the signer rejects a mismatching event (PRODUCER_PUBKEY_MISMATCH). */
    private final String producerPubkey;

    public TraceEventFactory(@Value("${cashu.mint.url:}") String mintUrl,
                             OperationIdRegistry operationIdRegistry,
                             TraceEventSigner signer) {
        this.mintUrl = mintUrl;
        this.operationIdRegistry = operationIdRegistry;
        this.producerPubkey = signer.publicKeyHex();
    }

    /** Build a {@code MINT_QUOTE_REQUESTED} event (0 inputs, 0 outputs). */
    public TransactionEvent buildMintQuoteRequested(TraceMintQuoteRequestedEvent e) {
        String operationId = operationIdRegistry.resolve("mint_quote", e.getQuoteId());
        LightningRef lightning = lightningRef(e.getQuoteId(), e.getRequest(), e.getPaymentHash(),
                e.getAmount(), e.getExpiry(), OperationKind.MINT_QUOTE_REQUESTED);
        return base(operationId, OperationKind.MINT_QUOTE_REQUESTED, unitOrDefault(e.getUnit()),
                e.getTransitionAt(), List.of(), List.of(), lightning,
                Optional.empty(), Optional.empty());
    }

    /** Build a {@code MELT_QUOTE_REQUESTED} event (0 inputs, 0 outputs, carries fee reserve). */
    public TransactionEvent buildMeltQuoteRequested(TraceMeltQuoteRequestedEvent e) {
        String operationId = operationIdRegistry.resolve("melt_quote", e.getQuoteId());
        LightningRef lightning = lightningRef(e.getQuoteId(), e.getRequest(), null,
                e.getAmount(), e.getExpiry(), OperationKind.MELT_QUOTE_REQUESTED);
        return base(operationId, OperationKind.MELT_QUOTE_REQUESTED, unitOrDefault(e.getUnit()),
                e.getTransitionAt(), List.of(), List.of(), lightning,
                Optional.of(e.getFeeReserve()), Optional.empty());
    }

    // --- shared helpers -----------------------------------------------------

    private LightningRef lightningRef(String quoteId, String bolt11, String paymentHash,
                                      long amount, int expiry, OperationKind quoteOperation) {
        Optional<Instant> expiresAt = expiry > 0
                ? Optional.of(Instant.ofEpochSecond(expiry)) : Optional.empty();
        return new LightningRef(
                quoteId,
                mintUrl,
                Optional.ofNullable(emptyToNull(bolt11)),
                Optional.ofNullable(emptyToNull(paymentHash)),
                Optional.of(amount),
                expiresAt,
                quoteOperation,
                false);
    }

    /**
     * Assemble a {@link TransactionEvent} with the mint-side defaults for the
     * many unused optional fields. {@code transitionAt} (ms) is the ledger's
     * ordering source of truth; {@code createdAt} is its second-resolution image.
     */
    private TransactionEvent base(String operationId, OperationKind kind, String unit,
                                  Instant transitionAt, List<ProofRef> inputs,
                                  List<ProofRef> outputs, LightningRef lightning,
                                  Optional<Long> feeAmount, Optional<String> errorCode) {
        Instant createdAt = Instant.ofEpochSecond(transitionAt.getEpochSecond());
        return new TransactionEvent(
                Optional.empty(),                 // eventId — SDK fills
                operationId,
                kind,
                mintUrl,
                unit,
                transitionAt,
                createdAt,
                producerPubkey,                   // must equal the signer's key (validated at sign)
                Optional.empty(),                 // initiatorPubkey — omitted (Principle VII)
                inputs,
                outputs,
                List.<OutputRole>of(),            // outputRoles
                Optional.of(lightning),
                Optional.empty(),                 // voucherRef
                Optional.empty(),                 // issuerId
                Optional.empty(),                 // issuerPubkey
                Optional.empty(),                 // bundleId
                Optional.empty(),                 // transferId
                feeAmount,
                errorCode,
                Optional.empty(),                 // errorMessage
                Optional.empty(),                 // correctionOf
                PrivacyMode.FULL,
                Optional.empty(),                 // redactionKeyId
                Optional.empty(),                 // overflowPolicy
                TransactionEvent.CURRENT_SCHEMA_VERSION,
                new NostrEventMetadata(Optional.empty(), NostrEventMetadata.TRACE_EVENT_KIND,
                        Optional.empty(), Optional.empty(), createdAt));
    }

    private static String unitOrDefault(String unit) {
        return (unit == null || unit.isBlank()) ? DEFAULT_UNIT : unit;
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
