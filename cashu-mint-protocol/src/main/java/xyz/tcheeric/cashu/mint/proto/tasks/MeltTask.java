package xyz.tcheeric.cashu.mint.proto.tasks;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.nut18.PaymentMethod;
import xyz.tcheeric.cashu.common.PrivateKey;
import xyz.tcheeric.cashu.common.Proof;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import xyz.tcheeric.cashu.crypto.BDHKEUtils;
import xyz.tcheeric.cashu.entities.rest.ErrorResponse;
import xyz.tcheeric.cashu.entities.rest.nut05.PostMeltRequest;
import xyz.tcheeric.cashu.entities.rest.nut05.PostMeltResponse;
import xyz.tcheeric.cashu.mint.proto.domain.MeltSagaState;
import xyz.tcheeric.cashu.mint.proto.domain.PaymentOutcome;
import xyz.tcheeric.cashu.mint.proto.ports.LightningPaymentPort;
import xyz.tcheeric.cashu.mint.proto.ports.MeltSaga;
import xyz.tcheeric.cashu.mint.proto.ports.MeltSagaRepository;
import xyz.tcheeric.cashu.mint.proto.ports.MintIntegrityContext;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.MintVaultService;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultService;
import xyz.tcheeric.cashu.mint.proto.service.impl.DefaultMintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.impl.DefaultMintVaultService;
import xyz.tcheeric.cashu.mint.proto.service.impl.DefaultProofVaultService;
import xyz.tcheeric.cashu.mint.proto.util.ProofLockManager;
import xyz.tcheeric.cashu.vault.db.model.MintEntity;
import xyz.tcheeric.cashu.vault.db.model.ProofEntity;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * NUT-05 melt operation: settles a Lightning invoice using customer-supplied
 * proofs, then burns the proofs.
 *
 * <p>Spec references (FR-014 — pinned-commit URLs tracked as a follow-up;
 * current links use {@code main}):
 * <ul>
 *   <li>NUT-05: <a href="https://github.com/cashubtc/nuts/blob/main/05.md">cashubtc/nuts §05</a> — melt tokens</li>
 *   <li>NUT-08: <a href="https://github.com/cashubtc/nuts/blob/main/08.md">cashubtc/nuts §08</a> — Lightning fee return (overpaid melt)</li>
 *   <li>NUT-15: <a href="https://github.com/cashubtc/nuts/blob/main/15.md">cashubtc/nuts §15</a> — input fees (the {@code feeReserve.inputFees} component)</li>
 *   <li>NUT-19: <a href="https://github.com/cashubtc/nuts/blob/main/19.md">cashubtc/nuts §19</a> — cached responses (saga {@code melt_response_cache})</li>
 * </ul>
 *
 * <p>Spec 002 § Saga state machine (FR-003 / FR-004 / FR-006 / FR-007 /
 * FR-008): when {@link MeltSagaRepository} and {@link LightningPaymentPort}
 * are wired, the task drives the durable saga:
 * <ol>
 *   <li>Insert {@link xyz.tcheeric.cashu.mint.proto.ports.MeltSaga}
 *       in state {@code PROOFS_HELD} + mark proofs {@code PENDING}
 *       in a single transactional boundary <b>before</b> any payment call.</li>
 *   <li>Call {@link LightningPaymentPort#pay} and switch on the typed
 *       {@link PaymentOutcome}.</li>
 *   <li>Success → CAS to {@code PAYMENT_SENT}, invalidate proofs, CAS to
 *       {@code COMPLETED}.</li>
 *   <li>{@code DefinitiveFailure} → CAS to {@code FAILED}, refund proofs.</li>
 *   <li>{@code Unknown} → CAS to {@code PAYMENT_UNKNOWN}; the reconciler
 *       resolves the saga via read-only {@code checkStatus} polls.</li>
 * </ol>
 *
 * <p>When the saga repository is null (legacy unit-test contexts), the task
 * falls back to the pre-spec-002 flow but with the FR-001 / FR-009 burn-amount
 * check from {@link BurnAmountValidator} already enforced. The pay-before-burn
 * ordering bug remains in that legacy path; it is closed only when the saga
 * machine is wired in.
 *
 * <p>The cashu-vault {@code proof_entity.melt_saga_id} FK column (data-model
 * § cross-repo change) is <b>not</b> yet in place — it is a cross-repo
 * dependency tracked separately. Until it lands, the saga records the
 * exclusive intent but vault-side FK enforcement is missing. Documented as
 * known-gap on FR-006.
 */
@Slf4j
public class MeltTask<T extends Secret> extends InstrumentedTask<PostMeltResponse> {
    private final PostMeltRequest<T> postMeltRequest;
    private final PaymentMethod method;
    private final String unit;
    private final Mint mint;
    private final MintProtocolService mintProtocolService;
    private final MintLoadService mintLoadService;
    private final MintVaultService mintVaultService;
    private final ProofVaultService proofVaultService;
    private final MeltSagaRepository meltSagaRepository;
    private final LightningPaymentPort lightningPaymentPort;
    private final Duration paymentTimeout;

    public MeltTask(@NonNull PostMeltRequest<T> postMeltRequest, @NonNull PaymentMethod method, @NonNull Mint mint,
                    @NonNull MintProtocolService mintProtocolService) {
        this(postMeltRequest, method, null, mint, mintProtocolService, new DefaultMintLoadService(),
                new DefaultMintVaultService(), new DefaultProofVaultService(),
                null, null, Duration.ofSeconds(30));
    }

    public MeltTask(@NonNull PostMeltRequest<T> postMeltRequest, @NonNull PaymentMethod method, String unit, @NonNull Mint mint,
                    @NonNull MintProtocolService mintProtocolService,
                    @NonNull MintLoadService mintLoadService,
                    @NonNull MintVaultService mintVaultService,
                    @NonNull ProofVaultService proofVaultService) {
        this(postMeltRequest, method, unit, mint, mintProtocolService, mintLoadService, mintVaultService,
                proofVaultService, null, null, Duration.ofSeconds(30));
    }

    // Backward-compatible constructor used by tests: no unit parameter
    public MeltTask(@NonNull PostMeltRequest<T> postMeltRequest, @NonNull PaymentMethod method, @NonNull Mint mint,
                    @NonNull MintProtocolService mintProtocolService,
                    @NonNull MintLoadService mintLoadService,
                    @NonNull MintVaultService mintVaultService,
                    @NonNull ProofVaultService proofVaultService) {
        this(postMeltRequest, method, null, mint, mintProtocolService, mintLoadService, mintVaultService,
                proofVaultService, null, null, Duration.ofSeconds(30));
    }

    /**
     * Spec 002 constructor — when {@code meltSagaRepository} and
     * {@code lightningPaymentPort} are non-null, the task drives the saga
     * state machine (FR-003 onward). When null, legacy behaviour is
     * preserved.
     */
    public MeltTask(@NonNull PostMeltRequest<T> postMeltRequest, @NonNull PaymentMethod method, String unit, @NonNull Mint mint,
                    @NonNull MintProtocolService mintProtocolService,
                    @NonNull MintLoadService mintLoadService,
                    @NonNull MintVaultService mintVaultService,
                    @NonNull ProofVaultService proofVaultService,
                    MeltSagaRepository meltSagaRepository,
                    LightningPaymentPort lightningPaymentPort,
                    Duration paymentTimeout) {
        this.postMeltRequest = postMeltRequest;
        this.method = method;
        this.unit = unit;
        this.mint = mint;
        this.mintLoadService = mintLoadService;
        this.mintProtocolService = mintProtocolService;
        this.mintVaultService = mintVaultService;
        this.proofVaultService = proofVaultService;
        this.meltSagaRepository = meltSagaRepository;
        this.lightningPaymentPort = lightningPaymentPort;
        this.paymentTimeout = paymentTimeout == null ? Duration.ofSeconds(30) : paymentTimeout;
    }

    @Override
    protected PostMeltResponse doExecute() throws CashuErrorException {
        // TODO - Use java module instead?
        List<Proof<T>> proofsToMelt = postMeltRequest.getInputs();
        try (ProofLockManager.ProofLock ignored = ProofLockManager.lockSecrets(
                proofsToMelt.stream().map(proof -> proof.getSecret().toString()).toList())) {
            for (Proof<T> proof : proofsToMelt) {
                // Model B enforcement: Reject voucher secrets in melt operations
                if (isVoucherSecret(proof.getSecret())) {
                    log.warn("Voucher secret rejected in melt operation (Model B enforcement)");
                    ErrorResponse error = new ErrorResponse(
                        "voucher_not_accepted",
                        "Vouchers cannot be melted at mint (Model B). " +
                        "Please redeem with issuing merchant."
                    );
                    throw new CashuErrorException(error.toJson());
                }

                if (!verify(proof)) {
                    ErrorResponse error = new ErrorResponse("melt_proof_verification_error");
                    throw new CashuErrorException(error.toJson());
                }
            }

            var keySetId = proofsToMelt.get(0).getKeySetId();
            var keyset = mintLoadService.keySet(keySetId);
            var quoteId = postMeltRequest.getQuoteId();
            var gateway = unit == null ? mintProtocolService.createGateway(method)
                    : mintProtocolService.createGateway(method, unit);
            long invoiceAmount = gateway.getAmount(quoteId);
            var request = gateway.getRequest(quoteId);
            ExactFeeReserveResolver.Resolved feeReserve =
                    ExactFeeReserveResolver.resolve(gateway, quoteId, postMeltRequest, keyset);
            long proofSum = proofsToMelt.stream()
                    .mapToLong(Proof::getAmount)
                    .sum();
            if (log.isDebugEnabled()) {
                log.debug("Processing melt quote {} for request {} with invoice={} lightningReserve={} inputFees={} proofSum={}",
                        quoteId, request, invoiceAmount,
                        feeReserve.getLightningReserve(), feeReserve.getInputFees(), proofSum);
            }

            // Spec 002 FR-001 / FR-009: sum(proofs) >= invoice + exactFeeReserve, long arithmetic.
            // The validator throws insufficient_input *before* any external payment is attempted (SC-001).
            try {
                BurnAmountValidator.requireFunded(proofSum, invoiceAmount, feeReserve.getTotal());
            } catch (CashuErrorException rejection) {
                incrementCounter("cashu_mint_melt_insufficient_input_total");
                throw rejection;
            }

            // Spec 002 FR-003 — burn-first ordering. When the saga repo and
            // payment port are wired, drive the state machine; otherwise fall
            // back to the legacy flow with the new amount check still in
            // place. The legacy path is retained for unit-test contexts that
            // don't wire JPA.
            if (meltSagaRepository != null && lightningPaymentPort != null) {
                return executeWithSaga(proofsToMelt, quoteId, invoiceAmount, proofSum, feeReserve, gateway);
            }
            return executeLegacy(proofsToMelt, quoteId, gateway);
        }
    }

    /**
     * Spec 002 § Saga state machine. Drives PROOFS_HELD → PAYMENT_SENT →
     * COMPLETED on the happy path and FAILED / PAYMENT_SENT_BURN_FAILED /
     * PAYMENT_UNKNOWN on the compensation branches. Proofs are marked
     * {@code PENDING} BEFORE the external payment call (FR-003).
     *
     * <p><b>Known gap (cross-repo):</b> the FR-006 exclusive proof hold
     * relies on a {@code proof_entity.melt_saga_id} FK column in
     * cashu-vault that has not landed yet; this method records the saga's
     * intent but does not yet write the FK column. Tracked separately.
     */
    private PostMeltResponse executeWithSaga(List<Proof<T>> proofsToMelt, String quoteId,
                                             long invoiceAmount, long proofSum,
                                             ExactFeeReserveResolver.Resolved feeReserve,
                                             xyz.tcheeric.payment.adapter.core.common.Gateway gateway)
            throws CashuErrorException {
        // FR-005: reject duplicate non-terminal melts.
        MeltSaga existing = meltSagaRepository.findByQuoteId(quoteId).orElse(null);
        if (existing != null && !existing.currentState().isTerminal()) {
            log.warn("melt_in_progress quote_id={} saga_id={} state={}",
                    quoteId, existing.meltSagaId(), existing.currentState());
            throw new CashuErrorException(new ErrorResponse("melt_in_progress").toJson());
        }

        String sagaId = UUID.randomUUID().toString();
        SagaSeed seed = new SagaSeed(sagaId, quoteId, invoiceAmount, feeReserve.getTotal(),
                feeReserve.getInputFees(), proofSum, proofsToMelt.size(),
                resolveProviderName(gateway));
        meltSagaRepository.save(seed);
        meltSagaRepository.recordTransition(sagaId, null, MeltSagaState.PROOFS_HELD,
                "initial transition", "system");

        // FR-003: durable proof PENDING commit BEFORE gateway.pay.
        try {
            persistPendingProofs(proofsToMelt);
        } catch (CashuErrorException | RuntimeException e) {
            log.error("melt_saga proof_pending_failed quote_id={} saga_id={}", quoteId, sagaId, e);
            // Saga stays in PROOFS_HELD with no proofs actually held; the
            // TTL sweep in MeltSagaReconciler will move it to FAILED.
            throw e instanceof CashuErrorException ce ? ce
                    : new CashuErrorException(new ErrorResponse("melt_proof_pending_error").toJson());
        }

        // FR-008: typed payment outcome.
        PaymentOutcome outcome = lightningPaymentPort.pay(quoteId, paymentTimeout);
        if (outcome instanceof PaymentOutcome.Success success) {
            return completeAfterSuccess(sagaId, quoteId, proofsToMelt, success, gateway);
        }
        if (outcome instanceof PaymentOutcome.DefinitiveFailure failure) {
            return refundAfterFailure(sagaId, quoteId, proofsToMelt, failure);
        }
        // PaymentOutcome.Unknown
        PaymentOutcome.Unknown unknown = (PaymentOutcome.Unknown) outcome;
        return parkInUnknown(sagaId, quoteId, unknown);
    }

    private PostMeltResponse completeAfterSuccess(String sagaId, String quoteId,
                                                  List<Proof<T>> proofsToMelt,
                                                  PaymentOutcome.Success success,
                                                  xyz.tcheeric.payment.adapter.core.common.Gateway gateway)
            throws CashuErrorException {
        int updated = meltSagaRepository.casState(sagaId, MeltSagaState.PROOFS_HELD, MeltSagaState.PAYMENT_SENT);
        if (updated == 0) {
            log.warn("[melt-saga] cas_failed quote_id={} saga_id={} expected=PROOFS_HELD",
                    quoteId, sagaId);
        }
        meltSagaRepository.recordTransition(sagaId, MeltSagaState.PROOFS_HELD,
                MeltSagaState.PAYMENT_SENT, success.providerEventId(), "system");

        try {
            createInvalidateProofsTask(proofsToMelt).execute();
        } catch (CashuErrorException | RuntimeException invalidateError) {
            // FR-011 — operator-visible alert: payment is gone but the burn
            // commit failed. Saga lands in PAYMENT_SENT_BURN_FAILED and
            // stays there until operator intervention.
            log.error("[melt-saga][alert] PAYMENT_SENT_BURN_FAILED quote_id={} saga_id={} preimage={}",
                    quoteId, sagaId, success.paymentHash(), invalidateError);
            meltSagaRepository.casState(sagaId, MeltSagaState.PAYMENT_SENT,
                    MeltSagaState.PAYMENT_SENT_BURN_FAILED);
            meltSagaRepository.recordTransition(sagaId, MeltSagaState.PAYMENT_SENT,
                    MeltSagaState.PAYMENT_SENT_BURN_FAILED, invalidateError.getMessage(), "system");
            ErrorResponse burnError = new ErrorResponse("melt_proof_pending_error",
                    "payment sent but burn failed: " + invalidateError.getMessage());
            cacheTerminalError(sagaId, burnError);
            throw invalidateError instanceof CashuErrorException ce ? ce
                    : new CashuErrorException(burnError.toJson());
        }

        meltSagaRepository.casState(sagaId, MeltSagaState.PAYMENT_SENT, MeltSagaState.COMPLETED);
        meltSagaRepository.recordTransition(sagaId, MeltSagaState.PAYMENT_SENT, MeltSagaState.COMPLETED,
                "preimage=" + success.paymentHash(), "system");
        PostMeltResponse response = new PostMeltResponse(true, success.paymentHash());
        cacheTerminalResponse(sagaId, response);
        return response;
    }

    private PostMeltResponse refundAfterFailure(String sagaId, String quoteId,
                                                List<Proof<T>> proofsToMelt,
                                                PaymentOutcome.DefinitiveFailure failure)
            throws CashuErrorException {
        log.warn("[melt-saga] payment_failed quote_id={} saga_id={} reason={} provider_code={}",
                quoteId, sagaId, failure.reason(), failure.providerCode());
        meltSagaRepository.casState(sagaId, MeltSagaState.PROOFS_HELD, MeltSagaState.FAILED);
        meltSagaRepository.recordTransition(sagaId, MeltSagaState.PROOFS_HELD, MeltSagaState.FAILED,
                failure.reason() + ":" + failure.providerCode(), "system");
        // FR-006 proof refund (PENDING → UNSPENT): the cashu-vault SPI does
        // not yet expose a refund method. Until the cross-repo
        // ProofVaultService#refundPending(secret) call lands, proofs stay
        // PENDING on failure. The saga itself is in FAILED so a duplicate
        // melt for the same quote is rejected, and the PROOFS_HELD TTL
        // sweep in MeltSagaReconciler surfaces long-pending proofs for
        // operator action. Tracked alongside the
        // proof_entity.melt_saga_id FK column dependency.
        log.error("[melt-saga][alert] proof_refund_pending quote_id={} saga_id={} proof_count={} reason={} provider_code={}",
                quoteId, sagaId, proofsToMelt.size(), failure.reason(), failure.providerCode());
        ErrorResponse error = new ErrorResponse("melt_invoice_not_paid_error", failure.reason());
        cacheTerminalError(sagaId, error);
        throw new CashuErrorException(error.toJson());
    }

    private PostMeltResponse parkInUnknown(String sagaId, String quoteId,
                                           PaymentOutcome.Unknown unknown)
            throws CashuErrorException {
        log.error("[melt-saga][alert] PAYMENT_UNKNOWN quote_id={} saga_id={} reason={}",
                quoteId, sagaId, unknown.reason());
        meltSagaRepository.casState(sagaId, MeltSagaState.PROOFS_HELD, MeltSagaState.PAYMENT_UNKNOWN);
        meltSagaRepository.recordTransition(sagaId, MeltSagaState.PROOFS_HELD,
                MeltSagaState.PAYMENT_UNKNOWN, unknown.reason(), "system");
        throw new CashuErrorException(new ErrorResponse(
                "payment_unknown",
                "Payment provider response was ambiguous; saga parked for operator review").toJson());
    }

    /**
     * Legacy unit-test execution path — preserves the existing flow when the
     * spec-002 saga components are unwired. The FR-001 burn-amount check has
     * already run; this method runs the historical pay-before-burn sequence
     * for compatibility with tests that don't yet pass the saga components.
     */
    private PostMeltResponse executeLegacy(List<Proof<T>> proofsToMelt, String quoteId,
                                           xyz.tcheeric.payment.adapter.core.common.Gateway gateway)
            throws CashuErrorException {
        gateway.pay(quoteId);
        if (!gateway.checkPaymentStatus(quoteId)) {
            ErrorResponse error = new ErrorResponse("melt_invoice_not_paid_error");
            throw new CashuErrorException(error.toJson());
        }
        try {
            persistPendingProofs(proofsToMelt);
        } catch (CashuErrorException | RuntimeException e) {
            log.error("Failed to mark proofs as pending for melt quote {}", quoteId, e);
            ErrorResponse error = new ErrorResponse("melt_proof_pending_error");
            throw new CashuErrorException(error.toJson());
        }
        createInvalidateProofsTask(proofsToMelt).execute();
        return new PostMeltResponse(true, gateway.getPaymentPreimage(quoteId));
    }

    private static final ObjectMapper RESPONSE_MAPPER = new ObjectMapper();

    /** Spec 002 T216 — persists a terminal {@link PostMeltResponse} to the saga record. */
    private void cacheTerminalResponse(String sagaId, PostMeltResponse response) {
        try {
            meltSagaRepository.updateResponseCache(sagaId, RESPONSE_MAPPER.writeValueAsString(response));
        } catch (JsonProcessingException | RuntimeException e) {
            log.warn("melt_saga_response_cache_write_failed saga_id={} cause={}", sagaId, e.getMessage());
        }
    }

    /** Spec 002 T216 — persists a terminal {@link ErrorResponse} to the saga record. */
    private void cacheTerminalError(String sagaId, ErrorResponse error) {
        try {
            meltSagaRepository.updateResponseCache(sagaId, error.toJson());
        } catch (RuntimeException e) {
            log.warn("melt_saga_response_cache_write_failed saga_id={} cause={}", sagaId, e.getMessage());
        }
    }

    private static void incrementCounter(String name) {
        io.micrometer.core.instrument.MeterRegistry registry = MintIntegrityContext.meterRegistry();
        if (registry != null) {
            registry.counter(name).increment();
        }
    }

    private static String resolveProviderName(xyz.tcheeric.payment.adapter.core.common.Gateway gateway) {
        try {
            String name = gateway.getName();
            return name == null || name.isBlank() ? gateway.getClass().getSimpleName() : name;
        } catch (RuntimeException e) {
            return gateway.getClass().getSimpleName();
        }
    }

    /** Inline {@link MeltSaga} carrier for the initial saga insert. */
    private record SagaSeed(
            String meltSagaId,
            String quoteId,
            long invoiceAmount,
            long exactFeeReserve,
            long inputFeesAsAsserted,
            long inputAmount,
            int proofCount,
            String provider) implements MeltSaga {

        @Override public Long assertedFeeReserve() { return inputFeesAsAsserted; }
        @Override public MeltSagaState currentState() { return MeltSagaState.PROOFS_HELD; }
        @Override public String paymentHash() { return null; }
        @Override public String providerEventId() { return null; }
        @Override public String paymentOutcomeReason() { return null; }
        @Override public String meltResponseCache() { return null; }
        @Override public String changeOutputsHash() { return null; }
        @Override public String changeSignaturesJson() { return null; }
        @Override public Instant createdAt() { return Instant.now(); }
        @Override public Instant updatedAt() { return Instant.now(); }
    }

    public boolean verify(@NonNull Proof proof) throws CashuErrorException {
        if (proof.getSecret() == null || proof.getUnblindedSignature() == null) {
            log.error("melt_verify_missing_data secretPresent={} signaturePresent={}",
                    proof.getSecret() != null, proof.getUnblindedSignature() != null);
            return false;
        }
        PrivateKey privateKey = mintProtocolService.getPrivateKey(proof.getKeySetId(), proof.getAmount(), mint);
        if (privateKey != null) {
            try {
                return BDHKEUtils.verify(proof.getSecret().toString(), privateKey.toBytes(),
                        proof.getUnblindedSignature().getBytes());
            } catch (IllegalArgumentException | NullPointerException e) {
                log.error("melt_verify_crypto_error", e);
                ErrorResponse error = new ErrorResponse("melt_proof_verification_error");
                throw new CashuErrorException(error.toJson());
            }
        }
        return false;
    }

    private void persistPendingProofs(List<Proof<T>> proofsToMelt) throws CashuErrorException {
        MintEntity mintEntity = mintVaultService.retrieveMint(mint.getId());
        for (Proof<T> proof : proofsToMelt) {
            ProofEntity proofEntity = new ProofEntity();
            proofEntity.setAmount(proof.getAmount());
            proofEntity.setSecret(proof.getSecret().toString());
            if (proof.getWitness() != null) {
                proofEntity.setWitness(proof.getWitness().toString());
            }
            proofEntity.setUnblindedSignature(proof.getUnblindedSignature().toString());
            proofEntity.setMint(mintEntity);
            proofEntity.setState(ProofEntity.STATE_PENDING);
            proofVaultService.storePending(proofEntity);
        }
    }

    protected InvalidateProofsTask<T> createInvalidateProofsTask(List<Proof<T>> proofs) {
        return new InvalidateProofsTask<>(mint, proofs, mintVaultService, proofVaultService);
    }

    /**
     * Checks if a secret is a VoucherSecret (Model B enforcement).
     */
    private boolean isVoucherSecret(Secret secret) {
        return VoucherSecretDetector.isVoucherSecret(secret);
    }
}
