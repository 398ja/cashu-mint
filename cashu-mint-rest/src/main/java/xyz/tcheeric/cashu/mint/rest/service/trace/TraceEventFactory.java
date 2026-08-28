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
import xyz.tcheeric.cashu.mint.rest.event.TraceMeltFailedEvent;
import xyz.tcheeric.cashu.mint.rest.event.TraceMeltQuoteRequestedEvent;
import xyz.tcheeric.cashu.mint.rest.event.TraceMintFailedEvent;
import xyz.tcheeric.cashu.mint.rest.event.TraceMintQuoteRequestedEvent;
import xyz.tcheeric.cashu.mint.rest.event.TraceProofInput;

/**
 * Spec 036 — maps internal trace application events to the SDK's
 * {@link TransactionEvent} wire shape. Pure mapping; performs no I/O.
 *
 * <p>Mint-side rules:
 * {@code producerPubkey} is set to the signer's public key
 * ({@code TraceEventSigner.publicKeyHex()}) because the signer rejects a
 * mismatching event; {@code initiatorPubkey} is omitted (Principle VII — no
 * customer identity); outputs are always empty; and any input {@code ProofRef}
 * carries the public {@code Y} only — never the plaintext secret.
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
                e.getAmount(), e.getExpiry(), e.getTransitionAt(), OperationKind.MINT_QUOTE_REQUESTED);
        return base(operationId, OperationKind.MINT_QUOTE_REQUESTED, unitOrDefault(e.getUnit()),
                e.getTransitionAt(), List.of(), List.of(), lightning,
                Optional.empty(), Optional.empty(), Optional.empty());
    }

    /** Build a {@code MELT_QUOTE_REQUESTED} event (0 inputs, 0 outputs, carries fee reserve). */
    public TransactionEvent buildMeltQuoteRequested(TraceMeltQuoteRequestedEvent e) {
        String operationId = operationIdRegistry.resolve("melt_quote", e.getQuoteId());
        LightningRef lightning = lightningRef(e.getQuoteId(), e.getRequest(), null,
                e.getAmount(), e.getExpiry(), e.getTransitionAt(), OperationKind.MELT_QUOTE_REQUESTED);
        return base(operationId, OperationKind.MELT_QUOTE_REQUESTED, unitOrDefault(e.getUnit()),
                e.getTransitionAt(), List.of(), List.of(), lightning,
                Optional.of(e.getFeeReserve()), Optional.empty(), Optional.empty());
    }

    /** Build a {@code MINT_FAILED} event (0 inputs, 0 outputs, carries error code). */
    public TransactionEvent buildMintFailed(TraceMintFailedEvent e) {
        String operationId = operationIdRegistry.resolve("mint_failed", e.getQuoteId());
        LightningRef lightning = lightningRef(e.getQuoteId(), null, null,
                e.getAmount(), 0, e.getTransitionAt(), OperationKind.MINT_QUOTE_REQUESTED);
        return base(operationId, OperationKind.MINT_FAILED, unitOrDefault(e.getUnit()),
                e.getTransitionAt(), List.of(), List.of(), lightning,
                Optional.empty(), Optional.ofNullable(emptyToNull(e.getErrorCode())),
                Optional.ofNullable(emptyToNull(e.getErrorMessage())));
    }

    /** Build a {@code MELT_FAILED} event (>=1 released inputs as Y only, 0 outputs). */
    public TransactionEvent buildMeltFailed(TraceMeltFailedEvent e) {
        String operationId = operationIdRegistry.resolve("melt_failed", e.getQuoteId());
        List<ProofRef> inputs = e.getInputs() == null ? List.of()
                : e.getInputs().stream().map(TraceEventFactory::inputProofRef).toList();
        LightningRef lightning = lightningRef(e.getQuoteId(), null, null,
                e.getAmount(), 0, e.getTransitionAt(), OperationKind.MELT_QUOTE_REQUESTED);
        return base(operationId, OperationKind.MELT_FAILED, unitOrDefault(e.getUnit()),
                e.getTransitionAt(), inputs, List.of(), lightning,
                Optional.empty(), Optional.ofNullable(emptyToNull(e.getErrorCode())),
                Optional.ofNullable(emptyToNull(e.getErrorMessage())));
    }

    // --- shared helpers -----------------------------------------------------

    /**
     * Map a released melt input to a {@link ProofRef} carrying the public
     * {@code Y} only — the plaintext secret is never emitted (Principle VII).
     */
    private static ProofRef inputProofRef(TraceProofInput in) {
        return new ProofRef(in.amount(), in.keysetId(), in.y(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    private LightningRef lightningRef(String quoteId, String bolt11, String paymentHash,
                                      long amount, int expiry, Instant transitionAt,
                                      OperationKind quoteOperation) {
        return new LightningRef(
                quoteId,
                mintUrl,
                Optional.ofNullable(emptyToNull(bolt11)),
                Optional.ofNullable(emptyToNull(paymentHash)),
                Optional.of(amount),
                resolveExpiresAt(expiry, transitionAt),
                quoteOperation,
                false);
    }

    /**
     * The mint's gateways return {@code expiry} as a <strong>relative</strong> TTL in seconds
     * (e.g. {@code cashu.expiry=15} or a 900s invoice timeout), not an absolute epoch. Convert it to
     * the absolute {@link Instant} the ledger expects: any value below the current epoch second is a
     * relative offset from {@code transitionAt}; a value that already looks like an absolute future
     * timestamp is passed through unchanged. {@code <= 0} means "no expiry".
     */
    private static Optional<Instant> resolveExpiresAt(int expiry, Instant transitionAt) {
        if (expiry <= 0) {
            return Optional.empty();
        }
        if (expiry < transitionAt.getEpochSecond()) {
            return Optional.of(transitionAt.plusSeconds(expiry));
        }
        return Optional.of(Instant.ofEpochSecond(expiry));
    }

    /**
     * Assemble a {@link TransactionEvent} with the mint-side defaults for the
     * many unused optional fields. {@code transitionAt} (ms) is the ledger's
     * ordering source of truth; {@code createdAt} is its second-resolution image.
     */
    private TransactionEvent base(String operationId, OperationKind kind, String unit,
                                  Instant transitionAt, List<ProofRef> inputs,
                                  List<ProofRef> outputs, LightningRef lightning,
                                  Optional<Long> feeAmount, Optional<String> errorCode,
                                  Optional<String> errorMessage) {
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
                errorMessage,
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
