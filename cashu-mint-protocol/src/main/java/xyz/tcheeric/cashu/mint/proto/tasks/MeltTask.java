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
import xyz.tcheeric.cashu.common.nut11.P2PKSecret;
import xyz.tcheeric.cashu.mint.proto.IouKeysets;
import xyz.tcheeric.cashu.mint.proto.domain.MeltSagaState;
import xyz.tcheeric.cashu.mint.proto.tasks.validator.P2PKSpendingCondition;
import xyz.tcheeric.cashu.mint.proto.tasks.validator.SpendingCondition;
import xyz.tcheeric.cashu.mint.proto.domain.PaymentOutcome;
import xyz.tcheeric.cashu.mint.proto.ports.LightningPaymentPort;
import xyz.tcheeric.cashu.mint.proto.ports.MeltSaga;
import xyz.tcheeric.cashu.mint.proto.ports.MeltSagaRepository;
import xyz.tcheeric.cashu.mint.proto.ports.MintIntegrityContext;
import xyz.tcheeric.cashu.common.BlindSignature;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.MintVaultService;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultService;
import xyz.tcheeric.cashu.mint.proto.service.SignatureVaultService;
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
    /**
     * Optional. When provided, the task issues NUT-08 change blind
     * signatures + persists them on the saga; when null, the NUT-08
     * branch is silently skipped (legacy behaviour). Threaded from
     * {@code MeltTokensTask} via the {@link MintIntegrityContext}.
     */
    private final SignatureVaultService signatureVaultService;

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
        this(postMeltRequest, method, unit, mint, mintProtocolService, mintLoadService,
                mintVaultService, proofVaultService, meltSagaRepository, lightningPaymentPort,
                paymentTimeout, null);
    }

    /**
     * Spec 002 T215 constructor — adds {@link SignatureVaultService} so
     * the task can issue NUT-08 change blind signatures + persist them
     * via {@link MeltSagaRepository#updateResponseCache} after
     * {@code COMPLETED}.
     */
    public MeltTask(@NonNull PostMeltRequest<T> postMeltRequest, @NonNull PaymentMethod method, String unit, @NonNull Mint mint,
                    @NonNull MintProtocolService mintProtocolService,
                    @NonNull MintLoadService mintLoadService,
                    @NonNull MintVaultService mintVaultService,
                    @NonNull ProofVaultService proofVaultService,
                    MeltSagaRepository meltSagaRepository,
                    LightningPaymentPort lightningPaymentPort,
                    Duration paymentTimeout,
                    SignatureVaultService signatureVaultService) {
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
        this.signatureVaultService = signatureVaultService;
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

                // Dalia Phase 9: zero-value IOU proofs cannot be melted (cashed out). Checked per
                // proof, so a mixed IOU+value melt cannot slip an IOU proof past a first-proof check.
                var proofKeySet = mintLoadService.keySet(proof.getKeySetId());
                if (IouKeysets.isIouKeyset(proofKeySet)) {
                    ErrorResponse error = new ErrorResponse(
                        "iou_not_meltable", "Zero-value IOU tokens cannot be melted.");
                    throw new CashuErrorException(error.toJson());
                }

                // Dalia Phase 9: enforce NUT-11 P2PK spend conditions at redemption (melt), not just at
                // swap — otherwise an escrow proof could be cashed out with no witness check. Escrow
                // secrets use SIG_INPUTS, so the melt change outputs are only consulted under SIG_ALL.
                if (proof.getSecret() instanceof P2PKSecret) {
                    @SuppressWarnings({"unchecked", "rawtypes"})
                    SpendingCondition condition = new P2PKSpendingCondition(postMeltRequest.getOutputs());
                    condition.verify(proof);
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
        // The findByQuoteId check above is non-atomic with the save: two
        // concurrent melts for the same quote could both pass the
        // existing-saga check and race to save. The
        // melt_saga.quote_id UNIQUE constraint catches the loser; translate
        // the DataIntegrityViolationException into the same
        // `melt_in_progress` response the explicit check produces, so
        // clients never see a 500 on the race.
        try {
            meltSagaRepository.save(seed);
        } catch (org.springframework.dao.DataIntegrityViolationException race) {
            log.warn("melt_in_progress race_lost quote_id={} attempted_saga_id={} cause={}",
                    quoteId, sagaId, race.getMessage());
            throw new CashuErrorException(new ErrorResponse("melt_in_progress").toJson());
        }
        meltSagaRepository.recordTransition(sagaId, null, MeltSagaState.PROOFS_HELD,
                "initial transition", "system");

        // FR-003 (spec 005): durable proof PENDING commit BEFORE gateway.pay.
        //
        // Single atomic insert-or-claim per proof: the vault either claims an
        // existing UNSPENT row or inserts a fresh row in PENDING bound to this
        // saga. Replaces the prior storePending + markPendingForSaga pair,
        // which could never bind freshly-inserted rows (the row was already
        // PENDING when the UNSPENT→PENDING CAS ran, so the CAS matched zero
        // rows and the saga continued to lightningPaymentPort.pay with no
        // durable hold).
        List<ProofEntity> normalizedProofs;
        try {
            normalizedProofs = buildNormalizedProofEntities(proofsToMelt);
        } catch (CashuErrorException | RuntimeException e) {
            log.error("melt_saga proof_normalize_failed quote_id={} saga_id={}", quoteId, sagaId, e);
            // No vault write happened yet — nothing to release. Fail closed
            // with the spec-005 terminal error before any payment.
            releaseAndFailClosed(sagaId, quoteId);
            throw new CashuErrorException(new ErrorResponse("proofs_not_bound").toJson());
        }
        int bound;
        try {
            bound = proofVaultService.insertOrClaimForSaga(
                    normalizedProofs, sagaId, java.util.UUID.fromString(mint.getId()));
        } catch (CashuErrorException | RuntimeException e) {
            // Vault unreachable / errored mid-claim. Some proofs may already
            // be bound; release them and fail closed BEFORE payment. Always
            // surface proofs_not_bound (the spec-005 terminal contract);
            // the original cause is logged here.
            log.error("melt_saga proof_pending_failed quote_id={} saga_id={}", quoteId, sagaId, e);
            releaseAndFailClosed(sagaId, quoteId);
            throw new CashuErrorException(new ErrorResponse("proofs_not_bound").toJson());
        }
        if (bound < proofsToMelt.size()) {
            // FR-006 fail-closed: insufficient durable hold (a proof was
            // already SPENT or held by another saga). Release any partial
            // claim and bail out BEFORE lightningPaymentPort.pay is reached.
            log.warn("[melt-saga] proofs_not_bound quote_id={} saga_id={} expected={} bound={}",
                    quoteId, sagaId, proofsToMelt.size(), bound);
            releaseAndFailClosed(sagaId, quoteId);
            throw new CashuErrorException(new ErrorResponse("proofs_not_bound").toJson());
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
        // Spec 002 — persist provider identifiers on the saga row so the
        // admin query endpoint / Envers audit history can correlate
        // saga ↔ provider receipts (payment_hash + provider_event_id are
        // both Envers-audited).
        try {
            meltSagaRepository.updateProviderMetadata(sagaId,
                    success.paymentHash(), success.providerEventId(), null);
        } catch (RuntimeException e) {
            log.warn("melt_saga_provider_metadata_write_failed saga_id={} cause={}",
                    sagaId, e.getMessage());
        }

        try {
            createInvalidateProofsTask(proofsToMelt).execute();
            // Spec 002 T011 — commit the saga binding to SPENT atomically
            // and clear melt_saga_id. The legacy invalidate already
            // flipped state=SPENT; this call is a no-op on state but
            // clears the binding for SC-002 reconciliation
            // (COMPLETED saga must have 0 held proofs).
            int spent = proofVaultService.commitSpentForSaga(sagaId);
            if (log.isDebugEnabled()) {
                log.debug("[melt-saga] saga_binding_committed saga_id={} spent={}",
                        sagaId, spent);
            }
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

        // Spec 002 T215 / FR-013 — NUT-08 overpaid-melt change return. Computed
        // against the persisted saga (NOT the in-memory request) so the
        // amounts come from the same source of truth that backed the burn-
        // amount check. The wallet-supplied `outputs` are signed only after
        // COMPLETED — never before — so a failed melt never leaks change
        // signatures.
        try {
            issueNut08Change(sagaId, quoteId, response);
        } catch (CashuErrorException | RuntimeException changeError) {
            // Change return is best-effort post-COMPLETED. The saga is
            // terminal; the operator alert surfaces the gap.
            log.error("[melt-saga][alert] nut08_change_issuance_failed saga_id={} quote_id={} cause={}",
                    sagaId, quoteId, changeError.getMessage());
        }

        cacheTerminalResponse(sagaId, response);
        return response;
    }

    /**
     * Spec 002 T215 — issues NUT-08 change for an overpaid melt. The wallet
     * supplies blinded outputs on the request; the mint signs the ones whose
     * amounts sum to at most {@code inputAmount - invoiceAmount -
     * exactFeeReserve} (any excess is forfeited). The signed change is
     * mutated onto {@code response.change} and persisted on the saga via
     * {@link MeltSagaRepository#updateResponseCache} along with the
     * {@code change_outputs_hash} for forensics.
     */
    private void issueNut08Change(String sagaId, String quoteId, PostMeltResponse response)
            throws CashuErrorException {
        if (signatureVaultService == null) {
            return; // not wired (unit-test contexts)
        }
        List<BlindedMessage> outputs = postMeltRequest.getOutputs();
        if (outputs == null || outputs.isEmpty()) {
            return; // wallet declined the NUT-08 change return
        }
        MeltSaga saga = meltSagaRepository.findById(sagaId).orElse(null);
        if (saga == null) {
            return;
        }
        long overpaid;
        try {
            overpaid = Math.subtractExact(
                    Math.subtractExact(saga.inputAmount(), saga.invoiceAmount()),
                    saga.exactFeeReserve());
        } catch (ArithmeticException overflow) {
            log.warn("nut08_change overflow saga_id={}", sagaId);
            return;
        }
        if (overpaid <= 0) {
            return; // not overpaid; nothing to sign
        }
        long sumRequested = 0L;
        for (BlindedMessage bm : outputs) {
            sumRequested = Math.addExact(sumRequested, bm.getAmount());
        }
        if (sumRequested > overpaid) {
            // Per NUT-08 the wallet MUST size outputs to <= overpayment.
            // Refuse to over-sign change; log + skip.
            log.warn("nut08_change outputs_exceed_overpayment saga_id={} sum_outputs={} overpaid={}",
                    sagaId, sumRequested, overpaid);
            return;
        }

        List<BlindSignature> changeSignatures = new java.util.ArrayList<>(outputs.size());
        for (BlindedMessage bm : outputs) {
            SignBlindedMessageTask sign = new SignBlindedMessageTask(
                    mint, bm, mintProtocolService, signatureVaultService);
            BlindSignature sig = sign.execute();
            changeSignatures.add(sig);
        }
        response.setChange(changeSignatures);

        // Persist NUT-08 forensics columns explicitly so admin queries
        // and Envers audit history can correlate change outputs with the
        // saga without parsing the response-cache JSON.
        try {
            String changeOutputsHash = computeChangeOutputsHash(outputs);
            String changeSignaturesJson = RESPONSE_MAPPER.writeValueAsString(changeSignatures);
            meltSagaRepository.updateChangeOutputs(sagaId, changeOutputsHash, changeSignaturesJson);
        } catch (com.fasterxml.jackson.core.JsonProcessingException | RuntimeException e) {
            log.warn("nut08_change forensics_write_failed saga_id={} cause={}",
                    sagaId, e.getMessage());
        }

        // Persist for NUT-19 cached-response replay (research R7) + forensics.
        try {
            meltSagaRepository.updateResponseCache(sagaId,
                    RESPONSE_MAPPER.writeValueAsString(response));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("nut08_change response_cache_write_failed saga_id={} cause={}",
                    sagaId, e.getMessage());
        }
        log.info("[melt-saga] nut08_change_issued saga_id={} quote_id={} overpaid={} signed={}",
                sagaId, quoteId, overpaid, changeSignatures.size());
    }

    /**
     * Spec 002 T215 — SHA-256 over the sorted {@code (amount, keyset_id, B_)}
     * tuples of the wallet-supplied change outputs. Same hashing rule as
     * {@code OutputsHash} for the mint quote path (spec 001), so admin
     * tooling has one canonical fingerprint shape across both flows.
     */
    private static String computeChangeOutputsHash(List<BlindedMessage> outputs) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            // Stable order: sort by (amount, keysetId, B_) to make the hash
            // independent of wire-order shuffling.
            List<BlindedMessage> sorted = new java.util.ArrayList<>(outputs);
            sorted.sort(java.util.Comparator
                    .<BlindedMessage>comparingInt(BlindedMessage::getAmount)
                    .thenComparing(b -> b.getKeySetId().toString())
                    .thenComparing(b -> b.getBlindedMessage().toString()));
            for (BlindedMessage bm : sorted) {
                digest.update(Integer.toString(bm.getAmount()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                digest.update((byte) ':');
                digest.update(bm.getKeySetId().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                digest.update((byte) ':');
                digest.update(bm.getBlindedMessage().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
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
        try {
            meltSagaRepository.updateProviderMetadata(sagaId,
                    null, null, failure.reason() + ":" + failure.providerCode());
        } catch (RuntimeException e) {
            log.warn("melt_saga_provider_metadata_write_failed saga_id={} cause={}",
                    sagaId, e.getMessage());
        }
        // Spec 002 T011 / FR-006 — proof refund (PENDING → UNSPENT). Flips
        // every proof bound to this saga back to UNSPENT atomically and
        // clears the melt_saga_id binding, so the wallet can retry the
        // same proofs in a future melt.
        boolean refundConfirmed = false;
        try {
            int refunded = proofVaultService.refundForSaga(sagaId);
            refundConfirmed = true;
            log.info("[melt-saga] proof_refund saga_id={} quote_id={} refunded={}",
                    sagaId, quoteId, refunded);
        } catch (CashuErrorException | RuntimeException refundError) {
            // Best-effort: log + alert if the vault is unreachable. The
            // PROOFS_HELD TTL sweep in MeltSagaReconciler is the safety
            // net for stuck-in-PENDING proofs (though here the saga is
            // already terminal FAILED, the sweep won't trigger; operator
            // tooling addresses this case).
            log.error("[melt-saga][alert] proof_refund_failed quote_id={} saga_id={} cause={}",
                    quoteId, sagaId, refundError.getMessage());
        }
        // Distinguish a confirmed release from an unconfirmed one. Only the
        // confirmed path is a clean "payment failed, proofs released" outcome
        // (melt_invoice_not_paid_error). If the refund could not be confirmed
        // (vault outage), surface a distinct code: the proofs may still be stuck
        // PENDING, so downstream consumers (e.g. the spec 036 forensic trace)
        // MUST NOT record them as released/retryable — operator tooling reconciles
        // the terminal-FAILED saga. HTTP status is unchanged (both map to 5xx).
        String code = refundConfirmed ? "melt_invoice_not_paid_error" : "melt_proof_refund_failed";
        ErrorResponse error = new ErrorResponse(code, failure.reason());
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
        try {
            meltSagaRepository.updateProviderMetadata(sagaId, null, null, unknown.reason());
        } catch (RuntimeException e) {
            log.warn("melt_saga_provider_metadata_write_failed saga_id={} cause={}",
                    sagaId, e.getMessage());
        }
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

    /**
     * Spec 005 — legacy unit-test entry. The spec-002 saga path no longer
     * uses this; {@link #buildNormalizedProofEntities} feeds the atomic
     * {@code insertOrClaimForSaga} call instead. Kept for the legacy
     * pay-before-burn path used when the saga repository is unwired
     * ({@link #executeLegacy}). The Y-coordinate normalization prevents
     * the raw-secret/Y duplicate-row regression even on the legacy path.
     */
    private void persistPendingProofs(List<Proof<T>> proofsToMelt) throws CashuErrorException {
        MintEntity mintEntity = mintVaultService.retrieveMint(mint.getId());
        for (ProofEntity proofEntity : buildNormalizedProofEntities(proofsToMelt, mintEntity)) {
            proofEntity.setState(ProofEntity.STATE_PENDING);
            proofVaultService.storePending(proofEntity);
        }
    }

    /**
     * Spec 005 — builds vault rows for the melt-saga atomic insert-or-claim
     * call with Y-coordinate normalization, matching the canonical identity
     * used by {@link xyz.tcheeric.cashu.mint.proto.util.MintProtocolUtil#toProofEntity}.
     * Mixing raw secrets and Y values here would let two rows refer to the
     * same logical proof, defeating the (mint_id, secret) uniqueness
     * guarantee in the vault.
     */
    private List<ProofEntity> buildNormalizedProofEntities(List<Proof<T>> proofsToMelt) throws CashuErrorException {
        MintEntity mintEntity = mintVaultService.retrieveMint(mint.getId());
        return buildNormalizedProofEntities(proofsToMelt, mintEntity);
    }

    private List<ProofEntity> buildNormalizedProofEntities(List<Proof<T>> proofsToMelt, MintEntity mintEntity) {
        java.util.List<ProofEntity> rows = new java.util.ArrayList<>(proofsToMelt.size());
        for (Proof<T> proof : proofsToMelt) {
            rows.add(ProofEntity.fromProof(proof, mintEntity));
        }
        return rows;
    }

    /**
     * Spec 005 — fail-closed release for the {@code proofs_not_bound} path.
     * Refunds any proofs already claimed by this saga, then drives the
     * terminal transition — but ONLY if the refund succeeded.
     *
     * <p>If {@code refundForSaga} throws (transient vault outage), the saga
     * is deliberately left in {@code PROOFS_HELD}: {@code MeltSagaReconciler}'s
     * {@code sweepStaleProofsHeld} only retries refunds for {@code PROOFS_HELD}
     * sagas, so forcing {@code FAILED} here would strand any partially-claimed
     * proofs in {@code PENDING} with no automatic recovery. The caller still
     * throws {@code proofs_not_bound} to the client; the saga becomes terminal
     * on a later sweep once the refund succeeds. The counter is incremented in
     * both cases for visibility.
     */
    private void releaseAndFailClosed(String sagaId, String quoteId) {
        incrementCounter("cashu_mint_melt_proofs_not_bound_total");
        boolean refunded;
        try {
            proofVaultService.refundForSaga(sagaId);
            refunded = true;
        } catch (CashuErrorException | RuntimeException refundError) {
            log.error("[melt-saga][alert] proofs_not_bound refund_failed quote_id={} saga_id={} cause={} "
                            + "— leaving saga PROOFS_HELD for reconciler sweep",
                    quoteId, sagaId, refundError.getMessage());
            refunded = false;
        }
        if (!refunded) {
            // Saga stays PROOFS_HELD; reconciler TTL sweep retries the refund
            // and drives the terminal transition. Do not cache a terminal
            // error on a non-terminal saga.
            return;
        }
        try {
            meltSagaRepository.casState(sagaId, MeltSagaState.PROOFS_HELD, MeltSagaState.FAILED);
            meltSagaRepository.recordTransition(sagaId, MeltSagaState.PROOFS_HELD, MeltSagaState.FAILED,
                    "proofs_not_bound", "system");
        } catch (RuntimeException e) {
            log.warn("[melt-saga] proofs_not_bound transition_write_failed quote_id={} saga_id={} cause={}",
                    quoteId, sagaId, e.getMessage());
        }
        cacheTerminalError(sagaId, new ErrorResponse("proofs_not_bound"));
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
