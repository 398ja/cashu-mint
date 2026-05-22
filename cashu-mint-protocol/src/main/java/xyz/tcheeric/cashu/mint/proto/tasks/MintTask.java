package xyz.tcheeric.cashu.mint.proto.tasks;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.cashu.common.BlindSignature;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.KeySet;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.nut18.PaymentMethod;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.common.util.SplittingService;
import xyz.tcheeric.cashu.entities.rest.ErrorResponse;
import xyz.tcheeric.cashu.entities.rest.nut04.PostMintRequest;
import xyz.tcheeric.cashu.entities.rest.nut04.PostMintResponse;
import xyz.tcheeric.cashu.mint.proto.ports.IssuanceRecord;
import xyz.tcheeric.cashu.mint.proto.ports.IssuanceRecordRepository;
import xyz.tcheeric.cashu.mint.proto.ports.MintQuote;
import xyz.tcheeric.cashu.mint.proto.ports.MintQuote.LifecycleState;
import xyz.tcheeric.cashu.mint.proto.ports.MintQuoteRepository;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.PaymentStatusChecker;
import xyz.tcheeric.cashu.mint.proto.service.SignatureVaultService;
import xyz.tcheeric.cashu.mint.proto.util.OutputsHash;
import xyz.tcheeric.cashu.mint.proto.util.QuoteLockManager;
import xyz.tcheeric.cashu.mint.proto.util.SecurityLimits;
import xyz.tcheeric.cashu.mint.proto.util.VoucherQuoteRegistry;
import xyz.tcheeric.payment.adapter.core.common.Gateway;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

// TEST - When mint_invoice_not_paid_error is thrown, signBlindedMessage is never invoked, else it is invoked for each blindedMessage in the request
@Slf4j
public class MintTask<T extends Secret> extends InstrumentedTask<PostMintResponse> {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final PostMintRequest<T> postMintRequest;
    private final PaymentMethod method;
    private final String unit;
    private final Mint mint;
    private final MintProtocolService mintProtocolService;
    private final SignatureVaultService signatureVaultService;
    private final PaymentStatusChecker paymentStatusChecker;
    private final MintQuoteRepository mintQuoteRepository;
    private final IssuanceRecordRepository issuanceRecordRepository;
    private final MeterRegistry meterRegistry;
    private final SplittingService splittingService = new SplittingService();


    public MintTask(@NonNull PostMintRequest<T> postMintRequest,
                    @NonNull PaymentMethod method,
                    @NonNull Mint mint,
                    @NonNull MintProtocolService mintProtocolService,
                    @NonNull SignatureVaultService signatureVaultService) {
        this(postMintRequest, method, null, mint, mintProtocolService, signatureVaultService, null, null, null, null);
    }

    public MintTask(@NonNull PostMintRequest<T> postMintRequest,
                    @NonNull PaymentMethod method,
                    String unit,
                    @NonNull Mint mint,
                    @NonNull MintProtocolService mintProtocolService,
                    @NonNull SignatureVaultService signatureVaultService) {
        this(postMintRequest, method, unit, mint, mintProtocolService, signatureVaultService, null, null, null, null);
    }

    public MintTask(@NonNull PostMintRequest<T> postMintRequest,
                    @NonNull PaymentMethod method,
                    String unit,
                    @NonNull Mint mint,
                    @NonNull MintProtocolService mintProtocolService,
                    @NonNull SignatureVaultService signatureVaultService,
                    PaymentStatusChecker paymentStatusChecker) {
        this(postMintRequest, method, unit, mint, mintProtocolService, signatureVaultService, paymentStatusChecker, null, null, null);
    }

    public MintTask(@NonNull PostMintRequest<T> postMintRequest,
                    @NonNull PaymentMethod method,
                    String unit,
                    @NonNull Mint mint,
                    @NonNull MintProtocolService mintProtocolService,
                    @NonNull SignatureVaultService signatureVaultService,
                    PaymentStatusChecker paymentStatusChecker,
                    MintQuoteRepository mintQuoteRepository,
                    IssuanceRecordRepository issuanceRecordRepository) {
        this(postMintRequest, method, unit, mint, mintProtocolService, signatureVaultService,
                paymentStatusChecker, mintQuoteRepository, issuanceRecordRepository, null);
    }

    /**
     * Spec 001 constructor: when {@code mintQuoteRepository} and
     * {@code issuanceRecordRepository} are non-null, MintTask enforces FR-001
     * (sum of output amounts == quote.amount), FR-002 (single-use via
     * compare-and-set lifecycle transitions), FR-010 (Gateway.getAmount
     * cross-check before the PAID→ISSUING transition), and FR-011 (one
     * IssuanceRecord per quote). When {@code meterRegistry} is non-null, the
     * task emits {@code cashu_mint_amount_mismatch_total} and
     * {@code cashu_mint_quote_cross_check_failures_total} counters tagged with
     * {@code path="mint"}. Production wires all four when
     * {@code cashu.mint.jpa.enabled=true}; legacy unit-test constructors leave
     * them null so the existing test surface keeps working.
     */
    public MintTask(@NonNull PostMintRequest<T> postMintRequest,
                    @NonNull PaymentMethod method,
                    String unit,
                    @NonNull Mint mint,
                    @NonNull MintProtocolService mintProtocolService,
                    @NonNull SignatureVaultService signatureVaultService,
                    PaymentStatusChecker paymentStatusChecker,
                    MintQuoteRepository mintQuoteRepository,
                    IssuanceRecordRepository issuanceRecordRepository,
                    MeterRegistry meterRegistry) {
        this.postMintRequest = postMintRequest;
        this.method = method;
        this.unit = unit;
        this.mint = mint;
        this.mintProtocolService = mintProtocolService;
        this.signatureVaultService = signatureVaultService;
        this.paymentStatusChecker = paymentStatusChecker;
        this.mintQuoteRepository = mintQuoteRepository;
        this.issuanceRecordRepository = issuanceRecordRepository;
        this.meterRegistry = meterRegistry;
    }

    @Override
    protected PostMintResponse doExecute() throws CashuErrorException {
        PostMintResponse result = new PostMintResponse();
        List<BlindedMessage> blindedMessages = Objects.requireNonNull(
                postMintRequest.getBlindedMessages(),
                "Blinded messages must not be null");

        // Security limit check (per Oracle Secure Coding Guidelines DOS-1)
        if (blindedMessages.size() > SecurityLimits.MAX_BLINDED_MESSAGES) {
            log.warn("mint_task too_many_outputs count={} max={}",
                    blindedMessages.size(), SecurityLimits.MAX_BLINDED_MESSAGES);
            ErrorResponse error = new ErrorResponse("too_many_outputs",
                    "Maximum " + SecurityLimits.MAX_BLINDED_MESSAGES + " outputs allowed");
            throw new CashuErrorException(error.toJson());
        }

        // If the invoice was not paid yet, Bob responds with a structured error.
        if (log.isDebugEnabled()) {
            log.debug("Starting mint task: method={} unit={} blindedMessages={}", method, unit,
                    blindedMessages.size());
        }

        String quoteId = postMintRequest.getQuoteId();

        // Per-quote lock: serializes concurrent requests for the same quote
        // to prevent double-mint attacks while allowing parallel minting of different quotes
        try (QuoteLockManager.QuoteLock quoteLock = QuoteLockManager.lockQuote(quoteId)) {

            // Voucher tokens use mock payment - no real bitcoin backing needed
            boolean isVoucherQuote = VoucherQuoteRegistry.isVoucherQuote(quoteId);

            if (isVoucherQuote) {
                // Vouchers are merchant IOUs - skip payment verification
                log.info("mint_task voucher_quote_detected quote_id={} mock_payment=true", quoteId);
            } else {
                // Regular tokens require real Lightning payment per NUT-04
                // First check webhook cache (instant), then fall back to gateway polling
                boolean paid;

                if (paymentStatusChecker != null && paymentStatusChecker.isPaid(quoteId)) {
                    log.debug("Payment confirmed via webhook cache: quoteId={}", quoteId);
                    paid = true;
                } else {
                    // Fall back to gateway polling
                    Gateway gateway = unit == null ? mintProtocolService.createGateway(method)
                            : mintProtocolService.createGateway(method, unit);
                    paid = gateway.checkPaymentStatus(quoteId);
                    if (log.isDebugEnabled()) {
                        log.debug("Payment status from gateway for quoteId={} paid={}", quoteId, paid);
                    }
                }

                if (!paid) {
                    ErrorResponse error = new ErrorResponse("mint_invoice_not_paid_error");
                    throw new CashuErrorException(error.toJson());
                }
            }

            // Spec 001 FR-001/FR-002/FR-010/FR-011: bind issuance to the
            // durable quote amount, cross-check it against the gateway, and
            // consume the quote at most once. When the JPA module is wired in,
            // the repository is non-null and amount-binding is enforced;
            // legacy unit-test contexts (repo == null) preserve the original
            // behavior.
            String outputsHash = null;
            MintQuote durableQuote = null;
            if (!isVoucherQuote && mintQuoteRepository != null) {
                durableQuote = mintQuoteRepository.findById(quoteId).orElse(null);
                if (durableQuote == null) {
                    log.warn("mint_task missing_durable_quote quote_id={}", quoteId);
                    throw new CashuErrorException(new ErrorResponse("quote_not_found").toJson());
                }
                long requestedTotal = blindedMessages.stream()
                        .mapToLong(BlindedMessage::getAmount)
                        .sum();
                if (requestedTotal != durableQuote.amount()) {
                    log.warn("mint_task amount_mismatch quote_id={} expected={} requested={}",
                            quoteId, durableQuote.amount(), requestedTotal);
                    incrementCounter("cashu_mint_amount_mismatch_total");
                    throw new CashuErrorException(
                            new ErrorResponse("amount_mismatch",
                                    "Sum of blinded output amounts must equal the quote amount").toJson());
                }

                // FR-010: cross-check the durable quote against the gateway's
                // own view. Fail-closed on disagreement (research R8).
                Gateway crossCheckGateway = unit == null
                        ? mintProtocolService.createGateway(method)
                        : mintProtocolService.createGateway(method, unit);
                Integer gatewayAmount;
                try {
                    gatewayAmount = crossCheckGateway.getAmount(quoteId);
                } catch (RuntimeException e) {
                    log.error("mint_task gateway_cross_check_failed quote_id={}", quoteId, e);
                    incrementCounter("cashu_mint_quote_cross_check_failures_total");
                    throw new CashuErrorException(
                            new ErrorResponse("quote_amount_cross_check_failed").toJson());
                }
                if (gatewayAmount == null || gatewayAmount.longValue() != durableQuote.amount()) {
                    log.error("mint_task gateway_cross_check_mismatch quote_id={} durable={} gateway={}",
                            quoteId, durableQuote.amount(), gatewayAmount);
                    incrementCounter("cashu_mint_quote_cross_check_failures_total");
                    throw new CashuErrorException(
                            new ErrorResponse("quote_amount_cross_check_failed").toJson());
                }

                outputsHash = OutputsHash.compute(blindedMessages);
                int updated = mintQuoteRepository.casLifecycle(quoteId, LifecycleState.PAID, LifecycleState.ISSUING);
                if (updated == 0) {
                    // Spec 001 T310 — another writer is between PAID and ISSUED.
                    // Poll briefly (bounded so a hung writer doesn't pin our request)
                    // and then either replay or surface issuance_in_progress.
                    PostMintResponse replay = awaitIssuedAndReplay(quoteId, outputsHash, durableQuote);
                    if (replay != null) {
                        return replay;
                    }
                    throw new CashuErrorException(new ErrorResponse("issuance_in_progress").toJson());
                }
            }

            // Check if this is a voucher quote and validate against face value
            Long voucherFaceValue = VoucherQuoteRegistry.getFaceValue(quoteId);
            if (voucherFaceValue != null) {
                // This is a voucher quote - validate total amount matches face value
                long totalBlindedAmount = blindedMessages.stream()
                        .mapToLong(BlindedMessage::getAmount)
                        .sum();

                if (totalBlindedAmount != voucherFaceValue) {
                    log.error("Voucher mint amount mismatch: quoteId={} expected={} actual={}",
                            quoteId, voucherFaceValue, totalBlindedAmount);
                    VoucherQuoteRegistry.removeFaceValue(quoteId); // Clean up on error
                    ErrorResponse error = new ErrorResponse("mint_amount_mismatch");
                    throw new CashuErrorException(error.toJson());
                }

                log.info("Voucher mint validated: quoteId={} faceValue={}", quoteId, voucherFaceValue);
            }

            // Vouchers allow arbitrary denominations (free splitting)
            // Regular tokens require power-of-2 denominations per NUT-00
            if (isVoucherQuote) {
                log.info("mint_task voucher_quote amount={} arbitrary_denominations=true",
                        blindedMessages.stream().mapToLong(BlindedMessage::getAmount).sum());
            } else {
                validateDenominations(blindedMessages, mint);
            }

            log.debug("Signing {} blinded messages...", blindedMessages.size());

            // Use standard keyset keys for both vouchers and regular tokens
            for (BlindedMessage bm : blindedMessages) {
                SignBlindedMessageTask signBlindedMessageTask = new SignBlindedMessageTask(
                        mint, bm, mintProtocolService, signatureVaultService);
                BlindSignature bSignature = signBlindedMessageTask.execute();
                result.addBlindSignature(bSignature);
                if (log.isDebugEnabled()) {
                    log.debug("Signed blinded message amount={} keySetId={}", bm.getAmount(), bm.getKeySetId());
                }
            }

            // Clean up voucher quote registry after successful minting
            if (voucherFaceValue != null) {
                VoucherQuoteRegistry.removeFaceValue(quoteId);
                log.debug("Cleaned up voucher quote from registry: quoteId={}", quoteId);
            }

            // FR-002 / FR-011 — persist the ledger row and finish the lifecycle
            // transition. We do this after signing so the row is only written
            // for successful issuances. On commit failure the quote stays in
            // ISSUING and surfaces to the operator (no auto-rollback because
            // signing has already happened; ISSUING is an operator-triage state).
            if (durableQuote != null && issuanceRecordRepository != null) {
                IssuanceRecordRow row = new IssuanceRecordRow(
                        quoteId,
                        outputsHash,
                        encodeSignatures(result.getBlindSignatures()),
                        firstKeysetId(blindedMessages),
                        durableQuote.amount());
                issuanceRecordRepository.insertIfAbsent(row);
                int closed = mintQuoteRepository.casLifecycle(quoteId, LifecycleState.ISSUING, LifecycleState.ISSUED);
                if (closed == 0) {
                    log.warn("mint_task lifecycle_close_failed quote_id={} expected_state=ISSUING", quoteId);
                }
            }

            return result;
        } // QuoteLock auto-released here
    }

    /** Backoff schedule for the ISSUING-state polling loop (spec 001 T310). */
    static final long[] ISSUING_POLL_BACKOFF_MS = {50L, 100L, 200L, 400L, 800L};

    /**
     * Spec 001 T310 — when the CAS PAID→ISSUING returns 0, another writer is
     * between PAID and ISSUED. We poll the durable state with bounded backoff
     * (total budget ~1.55s) and either replay the issued signatures (matching
     * {@code outputs_hash}), surface {@code quote_already_issued} (ISSUED with
     * different outputs), or return {@code null} so the caller can throw
     * {@code issuance_in_progress}.
     */
    private PostMintResponse awaitIssuedAndReplay(String quoteId, String outputsHash, MintQuote fallback)
            throws CashuErrorException {
        for (int attempt = 0; attempt <= ISSUING_POLL_BACKOFF_MS.length; attempt++) {
            MintQuote refreshed = mintQuoteRepository.findById(quoteId).orElse(fallback);
            LifecycleState state = refreshed.lifecycleState();
            if (state == LifecycleState.ISSUED) {
                if (issuanceRecordRepository == null) {
                    throw new CashuErrorException(new ErrorResponse("quote_already_issued").toJson());
                }
                IssuanceRecord existing = issuanceRecordRepository.findById(quoteId).orElse(null);
                if (existing != null && existing.outputsHash().equals(outputsHash)) {
                    log.info("[mint][replay] quote_id={} outputs_hash={} attempt={}",
                            quoteId, outputsHash, attempt);
                    incrementCounter("cashu_mint_idempotent_replay_total");
                    return decodeSignatures(existing.signaturesJson());
                }
                throw new CashuErrorException(new ErrorResponse("quote_already_issued").toJson());
            }
            if (state != LifecycleState.ISSUING) {
                // Some unexpected lifecycle (e.g. FAILED). Bail out.
                return null;
            }
            if (attempt == ISSUING_POLL_BACKOFF_MS.length) {
                break;
            }
            try {
                Thread.sleep(ISSUING_POLL_BACKOFF_MS[attempt]);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    private void incrementCounter(String name) {
        if (meterRegistry == null) {
            return;
        }
        meterRegistry.counter(name, "path", "mint").increment();
    }

    private static String firstKeysetId(List<BlindedMessage> outputs) {
        return outputs.get(0).getKeySetId().toString();
    }

    private static String encodeSignatures(List<BlindSignature> signatures) {
        try {
            return JSON.writeValueAsString(signatures);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize BlindSignature list", e);
        }
    }

    private PostMintResponse decodeSignatures(String json) throws CashuErrorException {
        try {
            List<BlindSignature> sigs = JSON.readValue(json,
                    JSON.getTypeFactory().constructCollectionType(List.class, BlindSignature.class));
            PostMintResponse replay = new PostMintResponse();
            sigs.forEach(replay::addBlindSignature);
            return replay;
        } catch (JsonProcessingException e) {
            log.error("[mint][replay] failed_to_decode_signatures", e);
            throw new CashuErrorException(new ErrorResponse("internal_error").toJson());
        }
    }

    /** Inline {@link IssuanceRecord} for the happy-path persistence call. */
    private record IssuanceRecordRow(
            String quoteId,
            String outputsHash,
            String signaturesJson,
            String keysetId,
            long totalAmount) implements IssuanceRecord {

        @Override
        public java.time.Instant issuedAt() {
            return null; // populated by the adapter / DB default
        }
    }

    private void validateDenominations(List<BlindedMessage> blindedMessages, Mint mint) throws CashuErrorException {
        if (blindedMessages == null || blindedMessages.isEmpty()) {
            throw new CashuErrorException("mint_request_missing_outputs");
        }

        // Most mint requests use 1-2 keysets; use small initial capacity
        Map<String, List<Integer>> outputsByKeyset = new HashMap<>(4);
        for (BlindedMessage message : blindedMessages) {
            if (message == null) {
                throw new CashuErrorException("mint_request_contains_null_output");
            }
            if (message.getKeySetId() == null) {
                throw new CashuErrorException("missing_keyset_id");
            }
            int amount = message.getAmount();
            if (amount <= 0) {
                throw new CashuErrorException("invalid_output_amount");
            }
            outputsByKeyset
                    .computeIfAbsent(message.getKeySetId().toString(), ignored -> new ArrayList<>())
                    .add(amount);
        }

        for (var entry : outputsByKeyset.entrySet()) {
            String keysetId = entry.getKey();
            KeySet keySet = findKeySet(mint, keysetId);
            Set<Integer> availableDenoms = keySet.getKeys() == null
                    ? Set.of()
                    : keySet.getKeys().getValues().keySet().stream()
                    .map(BigInteger::intValue)
                    .filter(value -> value > 0)
                    .collect(Collectors.toSet());
            long total = entry.getValue().stream().mapToLong(Integer::longValue).sum();
            List<Integer> expected;
            try {
                expected = splittingService.split(total, availableDenoms);
            } catch (IllegalStateException e) {
                throw new CashuErrorException("invalid_denominations");
            }
            List<Integer> actual = new ArrayList<>(entry.getValue());
            actual.sort(Comparator.reverseOrder());
            if (!actual.equals(expected)) {
                throw new CashuErrorException("invalid_denominations");
            }
        }
    }

    private KeySet findKeySet(Mint mint, String keysetId) throws CashuErrorException {
        return mint.getKeySets().stream()
                .filter(keySet -> keysetId.equals(keySet.getId()))
                .findFirst()
                .orElseThrow(() -> new CashuErrorException("keyset_not_found"));
    }
}
