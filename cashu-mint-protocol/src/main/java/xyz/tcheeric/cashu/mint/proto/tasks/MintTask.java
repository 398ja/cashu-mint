package xyz.tcheeric.cashu.mint.proto.tasks;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.cashu.common.BlindSignature;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.KeySet;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.nut18.PaymentMethod;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.nut00.CashuErrorCode;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.rest.nut04.PostMintRequest;
import xyz.tcheeric.cashu.entities.rest.nut04.PostMintResponse;
import xyz.tcheeric.cashu.mint.proto.IouKeysets;
import xyz.tcheeric.cashu.common.nut20.MintQuoteSignature;
import xyz.tcheeric.cashu.mint.proto.domain.VoucherLifecycleState;
import xyz.tcheeric.cashu.mint.proto.ports.IssuanceRecord;
import xyz.tcheeric.cashu.mint.proto.ports.IssuanceRecordRepository;
import xyz.tcheeric.cashu.mint.proto.ports.MintIntegrityContext;
import xyz.tcheeric.cashu.mint.proto.ports.MintSuspensionRepository;
import xyz.tcheeric.cashu.mint.proto.ports.MintQuote;
import xyz.tcheeric.cashu.mint.proto.ports.MintQuote.LifecycleState;
import xyz.tcheeric.cashu.mint.proto.ports.MintQuoteRepository;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherFunding;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherFundingResolver;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherIssuance;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherIssuanceRepository;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherQuote;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherQuoteRepository;
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
import xyz.tcheeric.cashu.mint.proto.metrics.MetricRecorders;
import xyz.tcheeric.cashu.mint.proto.metrics.VoucherRejectionReason;

import java.time.Instant;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

// TEST - When mint_invoice_not_paid_error is thrown, signBlindedMessage is never invoked, else it is invoked for each blindedMessage in the request
/**
 * NUT-04 mint operation: signs blinded outputs for a paid quote.
 *
 * <p>Spec references (FR-014 — pinned-commit URLs tracked as a follow-up;
 * current links use {@code main}):
 * <ul>
 *   <li>NUT-04: <a href="https://github.com/cashubtc/nuts/blob/main/04.md">cashubtc/nuts §04</a> — mint tokens</li>
 *   <li>NUT-19: <a href="https://github.com/cashubtc/nuts/blob/main/19.md">cashubtc/nuts §19</a> — cached responses / idempotent retry</li>
 *   <li>NUT-20: <a href="https://github.com/cashubtc/nuts/blob/main/20.md">cashubtc/nuts §20</a> — signed mint quote (where supported)</li>
 * </ul>
 *
 * <p>Spec 001 integrity contract implemented here:
 * <ul>
 *   <li>FR-001: {@code sum(outputs.amount) == quote.amount}; mismatches throw
 *       {@code amount_mismatch} and increment
 *       {@code cashu_mint_issuance_amount_mismatch_total}.</li>
 *   <li>FR-002 / FR-011: compare-and-set lifecycle transitions
 *       {@code PAID → ISSUING → ISSUED} with one append-only
 *       {@code IssuanceRecord} row per quote.</li>
 *   <li>FR-003: NUT-19 idempotent replay — same blinded outputs against an
 *       {@code ISSUED} quote return the previously signed promises; different
 *       outputs reject with {@code quote_already_issued}.</li>
 *   <li>FR-010: {@code Gateway.getAmount(quoteId)} cross-check before the
 *       {@code PAID → ISSUING} CAS; failures emit
 *       {@code cashu_mint_issuance_cross_check_failure_total}.</li>
 * </ul>
 */
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


    public MintTask(@NonNull PostMintRequest<T> postMintRequest,
                    @NonNull PaymentMethod method,
                    @NonNull Mint mint,
                    @NonNull MintProtocolService mintProtocolService,
                    @NonNull SignatureVaultService signatureVaultService) {
        this(postMintRequest, method, null, mint, mintProtocolService, signatureVaultService, null, null, null);
    }

    public MintTask(@NonNull PostMintRequest<T> postMintRequest,
                    @NonNull PaymentMethod method,
                    String unit,
                    @NonNull Mint mint,
                    @NonNull MintProtocolService mintProtocolService,
                    @NonNull SignatureVaultService signatureVaultService) {
        this(postMintRequest, method, unit, mint, mintProtocolService, signatureVaultService, null, null, null);
    }

    public MintTask(@NonNull PostMintRequest<T> postMintRequest,
                    @NonNull PaymentMethod method,
                    String unit,
                    @NonNull Mint mint,
                    @NonNull MintProtocolService mintProtocolService,
                    @NonNull SignatureVaultService signatureVaultService,
                    PaymentStatusChecker paymentStatusChecker) {
        this(postMintRequest, method, unit, mint, mintProtocolService, signatureVaultService, paymentStatusChecker, null, null);
    }

    /**
     * Spec 001 constructor: when {@code mintQuoteRepository} and
     * {@code issuanceRecordRepository} are non-null, MintTask enforces FR-001
     * (sum of output amounts == quote.amount), FR-002 (single-use via
     * compare-and-set lifecycle transitions), FR-010 (Gateway.getAmount
     * cross-check before the PAID→ISSUING transition), and FR-011 (one
     * IssuanceRecord per quote). Metrics are emitted through the typed
     * {@code IssuanceMetricsRecorder} port, not from here. Production wires
     * both repositories when
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
                    IssuanceRecordRepository issuanceRecordRepository) {
        this.postMintRequest = postMintRequest;
        this.method = method;
        this.unit = unit;
        this.mint = mint;
        this.mintProtocolService = mintProtocolService;
        this.signatureVaultService = signatureVaultService;
        this.paymentStatusChecker = paymentStatusChecker;
        this.mintQuoteRepository = mintQuoteRepository;
        this.issuanceRecordRepository = issuanceRecordRepository;
    }

    /**
     * Refuse to issue while the mint is suspended.
     *
     * <p>Only issuance is refused: swap and melt run through their own tasks and
     * stay available, so a suspended mint keeps honouring redemption and holders
     * can always exit. See ADR-0006 and ADR-0007.
     *
     * <p>With no suspension record — the JPA module disabled — the mint is never
     * suspended, matching how the other durable records degrade.
     */
    private void requireNotSuspended() throws CashuErrorException {
        final MintSuspensionRepository suspensions = MintIntegrityContext.mintSuspensionRepository();
        if (suspensions == null || mint == null) {
            return;
        }
        if (suspensions.isIssuanceSuspended(mint.getId())) {
            log.warn("mint_task issuance_refused reason=mint_suspended mint_id={}", mint.getId());
            throw new CashuErrorException(CashuErrorCode.mint_suspended,
                    "This mint is suspended and is not issuing new tokens. "
                            + "Existing tokens can still be swapped and melted.");
        }
    }

    @Override
    protected PostMintResponse doExecute() throws CashuErrorException {
        requireNotSuspended();

        PostMintResponse result = new PostMintResponse();
        List<BlindedMessage> blindedMessages = Objects.requireNonNull(
                postMintRequest.getBlindedMessages(),
                "Blinded messages must not be null");

        // Security limit check (per Oracle Secure Coding Guidelines DOS-1)
        if (blindedMessages.size() > SecurityLimits.MAX_BLINDED_MESSAGES) {
            log.warn("mint_task too_many_outputs count={} max={}",
                    blindedMessages.size(), SecurityLimits.MAX_BLINDED_MESSAGES);
                    throw new CashuErrorException(CashuErrorCode.too_many_outputs,
                    "Maximum " + SecurityLimits.MAX_BLINDED_MESSAGES + " outputs allowed");
        }

        // Spec 007 — reject a null output up front, before any stream/amount
        // computation. The downstream amount-sum / hash streams dereference
        // each output's amount, so a null element would otherwise NPE into a
        // 500 instead of the intended deterministic mint_request_contains_null_output.
        // A null-safe loop (not List.contains(null), which throws on the
        // immutable List.of(...) lists used by unit-test callers).
        for (BlindedMessage output : blindedMessages) {
            if (output == null) {
                throw new CashuErrorException(CashuErrorCode.mint_request_contains_null_output);
            }
        }

        // If the invoice was not paid yet, Bob responds with a structured error.
        if (log.isDebugEnabled()) {
            log.debug("Starting mint task: method={} unit={} blindedMessages={}", method, unit,
                    blindedMessages.size());
        }

        String quoteId = postMintRequest.getQuoteId();

        // Spec 041 REQ-MINT-3 (recovery contract) — strict quote-expiry
        // enforcement. Computes the absolute expiry from the gateway's
        // createdAt + TTL and rejects requests past it. Runs BEFORE the
        // per-quote lock and BEFORE the durable-quote branch so it fires
        // regardless of whether the JPA module is wired. Best-effort
        // (skipped silently) when the gateway can't return the timestamps;
        // the spec-041 ClientMintExpiryJob is the canonical gateway-side
        // authority either way.
        try {
            Gateway expiryGateway = unit == null
                    ? mintProtocolService.createGateway(method)
                    : mintProtocolService.createGateway(method, unit);
            Integer ttlSeconds = expiryGateway.getPaymentExpiry(quoteId);
            Instant createdAt = expiryGateway.getCreatedAt(quoteId);
            if (ttlSeconds != null && ttlSeconds > 0 && createdAt != null) {
                Instant expiresAt = createdAt.plusSeconds(ttlSeconds.longValue());
                if (Instant.now().isAfter(expiresAt) && !alreadyPaid(expiryGateway, quoteId)) {
                    log.info("mint_task quote_expired quote_id={} created_at={} ttl_seconds={} expires_at={}",
                            quoteId, createdAt, ttlSeconds, expiresAt);
                    MetricRecorders.issuance().quoteExpired();
                    throw new CashuErrorException(CashuErrorCode.quote_expired);
                }
            }
        } catch (CashuErrorException ce) {
            throw ce;
        } catch (RuntimeException e) {
            log.warn("mint_task expiry_check_skipped quote_id={} reason={}",
                    quoteId, e.getMessage());
        }

        // Per-quote lock: serializes concurrent requests for the same quote
        // to prevent double-mint attacks while allowing parallel minting of different quotes
        try (QuoteLockManager.QuoteLock quoteLock = QuoteLockManager.lockQuote(quoteId)) {

            // Spec 003 FR-013 — vouchers are a NON-STANDARD vendor extension
            // on top of NUT-04 (https://github.com/cashubtc/nuts/blob/main/04.md).
            // They MUST NOT be advertised under the NUT-06 `nuts` key
            // (Constitution II; enforced by VoucherNutAdvertisementGuardTest)
            // and the issuance path here is the divergence from the standard
            // NUT-04 mint flow: instead of validating a Lightning payment,
            // we validate against a durable VoucherFunding row.
            //
            // Spec 003 FR-003 — voucher classification MUST come from the
            // durable record when the JPA module is wired. The in-memory
            // VoucherQuoteRegistry is a read-through cache only and stays
            // cold after a restart, so it cannot be the source of truth
            // for write decisions.
            VoucherQuoteRepository voucherClassifier = MintIntegrityContext.voucherQuoteRepository();
            boolean isVoucherQuote = (voucherClassifier != null
                    && voucherClassifier.findById(quoteId).isPresent())
                    || VoucherQuoteRegistry.isVoucherQuote(quoteId);
            VoucherFundingContext voucherCtx = null;

            // Dalia Phase 9: a pure IOU issuance (every output on the zero-value IOU keyset) is
            // payment-exempt and quote-less — there is nothing to pay and no durable value quote.
            boolean isIouMint = isIouMint(blindedMessages, mint);

            if (isVoucherQuote) {
                // Spec 003 FR-002 — vouchers MUST trace to a durable funding row.
                // The legacy "skip payment check" path is gone when the JPA module
                // is wired; legacy unit-test contexts (repo == null) keep working
                // unchanged. resolveVoucherFunding also enforces the CAS gate
                // (rejects with quote_already_issued / issuance_in_progress when
                // the durable lifecycle has already advanced past FUNDED).
                voucherCtx = resolveVoucherFunding(quoteId);
                if (voucherCtx == null) {
                    log.info("mint_task voucher_quote_detected quote_id={} mock_payment=true (legacy context)", quoteId);
                } else {
                    log.info("mint_task voucher_quote_funded quote_id={} funding_id={} source={}",
                            quoteId, voucherCtx.funding.fundingId(), voucherCtx.funding.fundingSource());
                }
            } else if (isIouMint) {
                // Dalia Phase 9: zero-value IOU issuance is payment-exempt and quote-less (nothing to
                // pay, no value quote). It carries no monetary value, but it IS unbounded issuance —
                // gated only by the (engine-only, rate-limited) /v1/mint endpoint. The Dalia trust
                // layer must not treat an IOU marker as proof of anything beyond "the mint blind-signed
                // this"; authorization/anti-spam is the engine's + rate limiter's responsibility.
                log.info("mint_task iou_issuance quote_id={} zero_value=true", quoteId);
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
                    throw new CashuErrorException(CashuErrorCode.mint_invoice_not_paid_error);
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
            if (!isVoucherQuote && !isIouMint && mintQuoteRepository != null) {
                durableQuote = mintQuoteRepository.findById(quoteId).orElse(null);
                if (durableQuote == null) {
                    log.warn("mint_task missing_durable_quote quote_id={}", quoteId);
                    throw new CashuErrorException(CashuErrorCode.quote_not_found);
                }
                requireMintQuoteSignature(durableQuote, quoteId, blindedMessages);
                long requestedTotal = blindedMessages.stream()
                        .mapToLong(BlindedMessage::getAmount)
                        .sum();
                if (requestedTotal != durableQuote.amount()) {
                    log.warn("mint_task amount_mismatch quote_id={} expected={} requested={}",
                            quoteId, durableQuote.amount(), requestedTotal);
                    MetricRecorders.issuance().amountMismatch();
                    throw new CashuErrorException(CashuErrorCode.amount_mismatch,
                                    "Sum of blinded output amounts must equal the quote amount");
                }

                // FR-010: cross-check the durable quote against the gateway's
                // own view. Fail-closed on disagreement (research R8).
                Gateway crossCheckGateway = unit == null
                        ? mintProtocolService.createGateway(method)
                        : mintProtocolService.createGateway(method, unit);

                // Spec 041 REQ-MINT-3 (recovery contract): strict quote-expiry
                // enforcement. The gateway returns the quote's TTL (seconds)
                // and the durable quote carries its createdAt instant; compute
                // the absolute expiry and reject `quote_expired` once we're
                // past it. Without this, the gateway's ClientMintExpiryJob
                // is the only authority — fine as a fallback but the mint
                // shouldn't itself accept stale quotes.
                try {
                    Integer ttlSeconds = crossCheckGateway.getPaymentExpiry(quoteId);
                    Instant createdAt = durableQuote.createdAt();
                    if (ttlSeconds != null && ttlSeconds > 0 && createdAt != null) {
                        Instant expiresAt = createdAt.plusSeconds(ttlSeconds.longValue());
                        if (Instant.now().isAfter(expiresAt) && !alreadyPaid(crossCheckGateway, quoteId)) {
                            log.info("mint_task quote_expired quote_id={} created_at={} ttl_seconds={} expires_at={}",
                                    quoteId, createdAt, ttlSeconds, expiresAt);
                            MetricRecorders.issuance().quoteExpired();
                            throw new CashuErrorException(CashuErrorCode.quote_expired);
                        }
                    }
                } catch (CashuErrorException ce) {
                    throw ce;
                } catch (RuntimeException e) {
                    // getPaymentExpiry can throw if the quote lookup fails;
                    // log and fall through (downstream amount cross-check
                    // will catch a genuinely-missing quote with a clearer
                    // error). REQ-MINT-3 enforcement is best-effort when
                    // the gateway is uncooperative; the contract's gateway-
                    // side ClientMintExpiryJob remains the canonical
                    // authority.
                    log.warn("mint_task expiry_check_skipped quote_id={} reason={}",
                            quoteId, e.getMessage());
                }

                Integer gatewayAmount;
                try {
                    gatewayAmount = crossCheckGateway.getAmount(quoteId);
                } catch (RuntimeException e) {
                    log.error("mint_task gateway_cross_check_failed quote_id={}", quoteId, e);
                    MetricRecorders.issuance().crossCheckFailure();
                    throw new CashuErrorException(CashuErrorCode.quote_amount_cross_check_failed);
                }
                if (gatewayAmount == null || gatewayAmount.longValue() != durableQuote.amount()) {
                    log.error("mint_task gateway_cross_check_mismatch quote_id={} durable={} gateway={}",
                            quoteId, durableQuote.amount(), gatewayAmount);
                    MetricRecorders.issuance().crossCheckFailure();
                    throw new CashuErrorException(CashuErrorCode.quote_amount_cross_check_failed);
                }

                // Spec 007 — deterministic output validation MUST run before
                // the quote-consuming PAID → ISSUING CAS, so a malformed-but-
                // amount-summing request (e.g. a non-positive or non-canonical
                // output) can never consume a PAID quote into ISSUING and strand
                // it there. Gated on PAID: for an already-advanced quote the CAS
                // below fails and yields quote_already_issued / issuance_in_progress
                // (no stranding risk, and the single-issuance contract is
                // preserved over output-shape errors).
                if (durableQuote.lifecycleState() == LifecycleState.PAID) {
                    validateOutputs(blindedMessages, mint);
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
                    throw new CashuErrorException(CashuErrorCode.issuance_in_progress);
                }
            }

            // Spec 003 FR-001/FR-003 — voucher face_value validation reads
            // from the durable record when wired, falling back to the
            // in-memory registry only in legacy unit-test contexts. A cold
            // registry after restart MUST NOT silently skip the sum check.
            Long voucherFaceValue;
            if (voucherCtx != null) {
                voucherFaceValue = voucherCtx.quote.faceValue();
            } else {
                voucherFaceValue = VoucherQuoteRegistry.getFaceValue(quoteId);
            }
            if (voucherFaceValue != null) {
                long totalBlindedAmount = blindedMessages.stream()
                        .mapToLong(BlindedMessage::getAmount)
                        .sum();

                if (totalBlindedAmount != voucherFaceValue) {
                    log.error("Voucher mint amount mismatch: quoteId={} expected={} actual={}",
                            quoteId, voucherFaceValue, totalBlindedAmount);
                    VoucherQuoteRegistry.removeFaceValue(quoteId); // Clean up on error
                    throw new CashuErrorException(CashuErrorCode.mint_amount_mismatch);
                }

                log.info("Voucher mint validated: quoteId={} faceValue={}", quoteId, voucherFaceValue);
            }

            // Spec 007 — only now (after the deterministic face-value output-sum
            // check above) consume the voucher quote FUNDED → ISSUING. Done here
            // rather than inside resolveVoucherFunding so a malformed output set
            // can never strand the quote in ISSUING.
            if (voucherCtx != null) {
                advanceVoucherToIssuing(quoteId, voucherCtx.quote);
            }

            // Vouchers allow arbitrary denominations (free splitting). Regular
            // durable quotes were denomination-validated above, before the
            // PAID → ISSUING CAS (spec 007). The legacy regular path
            // (mintQuoteRepository == null, unit-test contexts) has no durable
            // CAS to guard, so it validates here.
            if (isVoucherQuote) {
                log.info("mint_task voucher_quote amount={} arbitrary_denominations=true",
                        blindedMessages.stream().mapToLong(BlindedMessage::getAmount).sum());
            } else if (mintQuoteRepository == null) {
                validateOutputs(blindedMessages, mint);
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

            // Spec 003 FR-002 / FR-005 — append the VoucherIssuance ledger row
            // and close the voucher lifecycle. Same operator-triage rule as
            // above: a commit failure leaves ISSUING so reconciliation can
            // surface it.
            if (voucherCtx != null) {
                String voucherOutputsHash = OutputsHash.compute(blindedMessages);

                // Spec 003 review fix — voucher quote_ids live in
                // voucher_quote, NOT mint_quote. The spec-001
                // issuance_record table has a FK to mint_quote(quote_id)
                // so writing a voucher quote_id into issuance_record
                // throws DataIntegrityViolationException. The voucher
                // ledger lives in voucher_issuance (below); we deliberately
                // do NOT touch issuance_record for the voucher path.
                //
                // NUT-19 idempotent replay for vouchers is a future
                // enhancement: a second mint call against an ISSUED
                // voucher quote is rejected via the CAS gate in
                // resolveVoucherFunding(), not via signature replay.

                VoucherIssuanceRepository voucherIssuanceRepo = MintIntegrityContext.voucherIssuanceRepository();
                if (voucherIssuanceRepo != null) {
                    voucherIssuanceRepo.insertIfAbsent(new VoucherIssuanceRow(
                            quoteId,
                            voucherCtx.funding.fundingId(),
                            quoteId,
                            voucherOutputsHash,
                            java.time.Instant.now()));
                }

                VoucherQuoteRepository voucherRepo = MintIntegrityContext.voucherQuoteRepository();
                if (voucherRepo != null) {
                    // Spec 035 — capture the original sat-denominated proof
                    // sum atomically with the lifecycle close. The wallet's
                    // receive-side endpoints downstream surface this as
                    // issuance_ratio = face_value / original_token_amount,
                    // letting the partial-spend correction fire on the
                    // receiver side without needing the sender to forward
                    // the ratio.
                    long originalTokenAmount = blindedMessages.stream()
                            .mapToLong(BlindedMessage::getAmount)
                            .sum();
                    int closed = voucherRepo.recordIssuance(quoteId, originalTokenAmount);
                    if (closed == 0) {
                        log.warn("mint_task voucher_lifecycle_close_failed quote_id={} expected_state=ISSUING", quoteId);
                    } else {
                        log.info("mint_task voucher_issued quote_id={} face_value={} original_token_amount={}",
                                quoteId, voucherCtx.quote.faceValue(), originalTokenAmount);
                    }
                }

                // Spec 003 FR-014 / T114 — per-funding-source success counter
                // for operator dashboards (SC-006 liability reconciliation).
                MetricRecorders.voucher().issued(voucherCtx.funding.fundingSource());
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
                    throw new CashuErrorException(CashuErrorCode.quote_already_issued);
                }
                IssuanceRecord existing = issuanceRecordRepository.findById(quoteId).orElse(null);
                if (existing != null && existing.outputsHash().equals(outputsHash)) {
                    log.info("[mint][replay] quote_id={} outputs_hash={} attempt={}",
                            quoteId, outputsHash, attempt);
                    MetricRecorders.issuance().idempotentReplay();
                    return decodeSignatures(existing.signaturesJson());
                }
                throw new CashuErrorException(CashuErrorCode.quote_already_issued);
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
            throw new CashuErrorException(CashuErrorCode.internal_error);
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

    /** Spec 003 — inline {@link VoucherIssuance} for the happy-path persistence call. */
    private record VoucherIssuanceRow(
            String voucherQuoteId,
            String fundingId,
            String issuanceId,
            String outputsHash,
            java.time.Instant issuedAt) implements VoucherIssuance {}

    /** Carrier for the voucher branch state after the funding gate clears. */
    private record VoucherFundingContext(VoucherQuote quote, VoucherFunding funding) {}

    /**
     * Spec 003 FR-002 / FR-005 — voucher funding gate. Returns the funding
     * context (quote + funding row) when issuance is allowed; throws
     * {@code funding_required} when the durable voucher quote exists but
     * no funding row resolves. Returns {@code null} when the JPA module
     * is not wired (legacy unit-test contexts).
     */
    private VoucherFundingContext resolveVoucherFunding(String quoteId) throws CashuErrorException {
        VoucherQuoteRepository voucherRepo = MintIntegrityContext.voucherQuoteRepository();
        if (voucherRepo == null) {
            return null;
        }

        VoucherQuote quote = voucherRepo.findById(quoteId).orElse(null);
        if (quote == null) {
            log.warn("mint_task voucher_quote_missing quote_id={}", quoteId);
            throw new CashuErrorException(CashuErrorCode.voucher_quote_not_found);
        }

        // Spec 003 review fix — single-issuance invariant. A second mint
        // request against a voucher quote that has already advanced past
        // FUNDED MUST NOT re-sign. NUT-19 idempotent signature replay for
        // vouchers is a future enhancement; today we reject.
        VoucherLifecycleState state = quote.lifecycleState();
        if (state == VoucherLifecycleState.ISSUED) {
            log.info("mint_task voucher_quote_already_issued quote_id={}", quoteId);
            throw new CashuErrorException(CashuErrorCode.quote_already_issued);
        }
        if (state == VoucherLifecycleState.ISSUING) {
            log.info("mint_task voucher_issuance_in_progress quote_id={}", quoteId);
            throw new CashuErrorException(CashuErrorCode.issuance_in_progress);
        }
        if (state == VoucherLifecycleState.EXPIRED || state == VoucherLifecycleState.FAILED) {
            log.info("mint_task voucher_quote_unavailable quote_id={} state={}", quoteId, state);
            throw new CashuErrorException(CashuErrorCode.quote_expired);
        }

        VoucherFunding funding = null;
        if (quote.fundingId() != null) {
            funding = MintIntegrityContext.voucherFundingRepository() != null
                    ? MintIntegrityContext.voucherFundingRepository().findById(quote.fundingId()).orElse(null)
                    : null;
        } else {
            VoucherFundingResolver resolver = MintIntegrityContext.voucherFundingResolver();
            if (resolver != null) {
                funding = resolver.resolveForQuote(quote).orElse(null);
                if (funding != null) {
                    int attached = voucherRepo.attachFundingAndAdvance(quoteId, funding.fundingId());
                    if (attached == 0) {
                        // Race: another writer attached. Re-read.
                        quote = voucherRepo.findById(quoteId).orElse(quote);
                    } else {
                        quote = voucherRepo.findById(quoteId).orElse(quote);
                    }
                }
            }
        }

        if (funding == null) {
            log.warn("mint_task voucher_funding_required quote_id={}", quoteId);
            MetricRecorders.voucher().rejected(VoucherRejectionReason.FUNDING_REQUIRED);
            throw new CashuErrorException(CashuErrorCode.funding_required);
        }

        // Spec 006 — fail-closed value-backing invariant + FR-006 IOU policy.
        // The funding row attached to this quote MUST cover the FACE VALUE
        // (in the quote's unit) before any promise is signed; a customer
        // fee-payment can never back face value. Runs BEFORE the
        // FUNDED → ISSUING CAS so a denied quote stays FUNDED (triageable)
        // and never advances or signs. Closes the over-issuance hole where
        // the mint signed full face-value Cashu against a fee-only payment.
        enforceFaceValueBacking(quoteId, quote, funding);

        // Spec 007 — the FUNDED → ISSUING CAS is NOT done here. It is the
        // quote-consuming transition and must run only AFTER the deterministic
        // face-value output-sum validation in doExecute (otherwise a malformed
        // output set strands the quote in ISSUING). This method resolves and
        // backing-gates the funding, then returns; doExecute calls
        // advanceVoucherToIssuing once the outputs are validated.
        return new VoucherFundingContext(quote, funding);
    }

    /**
     * Spec 007 — consume a FUNDED voucher quote into ISSUING. Called from
     * {@code doExecute} only AFTER the face-value output-sum check, so a
     * deterministically-invalid request can never strand the quote in
     * ISSUING. Mirrors the single-issuance race handling that previously
     * lived at the tail of {@link #resolveVoucherFunding}.
     */
    private void advanceVoucherToIssuing(String quoteId, VoucherQuote quote) throws CashuErrorException {
        VoucherQuoteRepository voucherRepo = MintIntegrityContext.voucherQuoteRepository();
        if (voucherRepo == null || quote.lifecycleState() != VoucherLifecycleState.FUNDED) {
            return;
        }
        int advanced = voucherRepo.casLifecycle(quoteId,
                VoucherLifecycleState.FUNDED, VoucherLifecycleState.ISSUING);
        if (advanced == 0) {
            // Another writer advanced the state between resolution and this CAS.
            // Re-read and reject — we MUST NOT sign (single-issuance invariant).
            VoucherQuote refreshed = voucherRepo.findById(quoteId).orElse(quote);
            VoucherLifecycleState newState = refreshed.lifecycleState();
            log.info("mint_task voucher_lifecycle_advance_lost quote_id={} observed_state={}",
                    quoteId, newState);
            if (newState == VoucherLifecycleState.ISSUED) {
                throw new CashuErrorException(CashuErrorCode.quote_already_issued);
            }
            throw new CashuErrorException(CashuErrorCode.issuance_in_progress);
        }
    }

    /**
     * Spec 006 — fail-closed face-value backing gate (+ FR-006 IOU policy).
     *
     * <p>Before a voucher is signed, the funding row attached to its quote
     * must durably back the <b>face value</b> in the quote's unit:
     * <ul>
     *   <li>{@code CUSTOMER_PAYMENT} — for a {@code customer_paid} voucher this
     *       IS the backing: the customer's payment locks the exchange rate the
     *       face value is derived from, and the mint holds no collateral behind
     *       it. Accepted. For any other voucher type a customer fee-payment
     *       ({@code amount == charged_amount = fee}) still cannot back the face
     *       value and is rejected with {@code face_value_not_backed}.</li>
     *   <li>{@code MERCHANT_IOU} — permitted only when
     *       {@code cashu.mint.voucher.iou-policy} is {@code ALLOW}; otherwise
     *       rejected with {@code iou_not_permitted} (the policy was installed
     *       into {@link MintIntegrityContext} but previously never read). When
     *       allowed it must still cover the face value.</li>
     *   <li>{@code MERCHANT_DEBIT} (and policy-allowed {@code MERCHANT_IOU}) —
     *       must satisfy {@code amount >= face_value} and matching unit.</li>
     * </ul>
     *
     * <p>Throws before the {@code FUNDED → ISSUING} CAS, so a denied quote
     * stays {@code FUNDED} and no promises are signed.
     */
    private void enforceFaceValueBacking(String quoteId, VoucherQuote quote, VoucherFunding funding)
            throws CashuErrorException {
        xyz.tcheeric.cashu.mint.proto.domain.VoucherFundingSource source = funding.fundingSource();

        if (source == xyz.tcheeric.cashu.mint.proto.domain.VoucherFundingSource.CUSTOMER_PAYMENT) {
            // A customer_paid voucher is not collateralised, and is not meant to be. The
            // customer's payment locks an exchange rate; the face value is derived from that
            // rate rather than held in reserve behind it. Demanding amount >= faceValue here
            // compares a charged fee against a face value and can never pass, which is what
            // stranded every issuance on staging with face_value_not_backed.
            //
            // The amount is still checked below against charged_amount, so a customer who
            // underpays their own quote is still refused.
            if (VOUCHER_TYPE_CUSTOMER_PAID.equalsIgnoreCase(quote.voucherType())) {
                enforceChargedAmountPaid(quoteId, quote, funding);
                return;
            }

            // Any other voucher type does expect collateral, and a fee-payment is not it.
            log.warn("[voucher][alert] face_value_not_backed quote_id={} funding_source=CUSTOMER_PAYMENT "
                            + "voucher_type={} face_value={} funding_amount={}",
                    quoteId, quote.voucherType(), quote.faceValue(), funding.amount());
            MetricRecorders.voucher().rejected(VoucherRejectionReason.FACE_VALUE_NOT_BACKED);
            throw new CashuErrorException(CashuErrorCode.face_value_not_backed);
        }

        if (source == xyz.tcheeric.cashu.mint.proto.domain.VoucherFundingSource.MERCHANT_IOU) {
            // Spec 003 FR-014 — operator alert for EVERY IOU issuance attempt,
            // regardless of policy outcome. Emitted BEFORE the policy gate so
            // existing dashboards / runbooks wired to this counter keep firing
            // on denied attempts too (the deny path throws below). The
            // policy-specific subset is the IOU_NOT_PERMITTED reason on
            // cashu_mint_voucher_rejected_total.
            log.warn("voucher_issuance MERCHANT_IOU quote_id={} funding_id={} merchant_id={} iou_id={} policy_profile={}",
                    quoteId, funding.fundingId(), funding.merchantId(), funding.iouId(), funding.policyProfile());
            MetricRecorders.voucher().iouIssuanceAttempted();
            // FR-006 — enforce the configured IOU policy (default DENY).
            String iouPolicy = MintIntegrityContext.voucherIouPolicy();
            if (!"ALLOW".equalsIgnoreCase(iouPolicy)) {
                log.error("[voucher][alert] iou_not_permitted quote_id={} funding_id={} policy={}",
                        quoteId, funding.fundingId(), iouPolicy);
                MetricRecorders.voucher().rejected(VoucherRejectionReason.IOU_NOT_PERMITTED);
                throw new CashuErrorException(CashuErrorCode.iou_not_permitted);
            }
        }

        // MERCHANT_DEBIT, and MERCHANT_IOU that cleared the policy gate, must
        // cover the full face value in the quote's unit.
        if (funding.amount() < quote.faceValue()
                || !java.util.Objects.equals(funding.unit(), quote.unit())) {
            log.warn("[voucher][alert] face_value_not_backed quote_id={} funding_source={} "
                            + "face_value={} funding_amount={} quote_unit={} funding_unit={}",
                    quoteId, source, quote.faceValue(), funding.amount(), quote.unit(), funding.unit());
            MetricRecorders.voucher().rejected(VoucherRejectionReason.FACE_VALUE_NOT_BACKED);
            throw new CashuErrorException(CashuErrorCode.face_value_not_backed);
        }
    }

    /**
     * Runs every deterministic output check the mint owes a wallet before it signs.
     *
     * <p>The shared protocol rules (duplicate outputs, mixed units, inactive keysets) come
     * first, then the NUT-04 denomination rule. Already-signed outputs are deliberately not
     * refused here: NUT-19 makes replaying the same outputs against an issued quote the
     * intended retry, and MintTask answers that from its own cache.
     */
    private void validateOutputs(List<BlindedMessage> blindedMessages, Mint mint) throws CashuErrorException {
        new ValidateTransactionTask<T>(null, blindedMessages, KeySetDirectory.of(mint), null).execute();
        validateDenominations(blindedMessages, mint);
    }

    private void validateDenominations(List<BlindedMessage> blindedMessages, Mint mint) throws CashuErrorException {
        // Spec 007 — these are deterministic client errors (computable from the
        // request + static keyset config). Throw typed ErrorResponse JSON so the
        // controller surfaces a clean 4xx with the code rather than mapping a
        // raw-string exception to a misleading internal_error/500.
        if (blindedMessages == null || blindedMessages.isEmpty()) {
            throw new CashuErrorException(CashuErrorCode.mint_request_missing_outputs);
        }

        // Most mint requests use 1-2 keysets; use small initial capacity
        Map<String, List<Integer>> outputsByKeyset = new HashMap<>(4);
        for (BlindedMessage message : blindedMessages) {
            if (message == null) {
                throw new CashuErrorException(CashuErrorCode.mint_request_contains_null_output);
            }
            if (message.getKeySetId() == null) {
                throw new CashuErrorException(CashuErrorCode.missing_keyset_id);
            }
            int amount = message.getAmount();
            // Dalia Phase 9: the zero-value IOU keyset issues only amount==0 markers and is exempt
            // from the standard positive-denomination split; every other keyset keeps rejecting 0.
            KeySet messageKeySet = findKeySet(mint, message.getKeySetId().toString());
            if (IouKeysets.isIouKeyset(messageKeySet)) {
                if (amount != 0) {
                    throw new CashuErrorException(CashuErrorCode.invalid_iou_amount);
                }
                continue;
            }
            if (amount <= 0) {
                throw new CashuErrorException(CashuErrorCode.invalid_output_amount);
            }
            outputsByKeyset
                    .computeIfAbsent(message.getKeySetId().toString(), ignored -> new ArrayList<>())
                    .add(amount);
        }

        for (var entry : outputsByKeyset.entrySet()) {
            KeySet keySet = findKeySet(mint, entry.getKey());
            Set<Integer> offeredDenominations = offeredDenominations(keySet);
            for (Integer amount : entry.getValue()) {
                if (!offeredDenominations.contains(amount)) {
                    throw new CashuErrorException(CashuErrorCode.invalid_denominations);
                }
            }
        }
    }

    /**
     * The positive amounts this keyset holds a key for.
     *
     * <p>NUT-04 asks only that each blinded output carry a denomination the keyset can
     * sign, and that the outputs sum to the quote amount. It does not prescribe how the
     * wallet splits that sum, so any combination of offered denominations is acceptable.
     */
    private Set<Integer> offeredDenominations(KeySet keySet) {
        if (keySet.getKeys() == null) {
            return Set.of();
        }
        return keySet.getKeys().getValues().keySet().stream()
                .map(BigInteger::intValue)
                .filter(amount -> amount > 0)
                .collect(Collectors.toSet());
    }

    /** Whether every blinded message targets the zero-value IOU keyset (Dalia Phase 9). */
    private boolean isIouMint(List<BlindedMessage> blindedMessages, Mint mint) {
        if (blindedMessages == null || blindedMessages.isEmpty()) {
            return false;
        }
        for (BlindedMessage message : blindedMessages) {
            if (message == null || message.getKeySetId() == null) {
                return false;
            }
            KeySet keySet = mint.getKeySets().stream()
                    .filter(ks -> message.getKeySetId().toString().equals(ks.getId()))
                    .findFirst()
                    .orElse(null);
            if (!IouKeysets.isIouKeyset(keySet)) {
                return false;
            }
        }
        return true;
    }

    private KeySet findKeySet(Mint mint, String keysetId) throws CashuErrorException {
        return mint.getKeySets().stream()
                .filter(keySet -> keysetId.equals(keySet.getId()))
                .findFirst()
                .orElseThrow(() -> new CashuErrorException(CashuErrorCode.keyset_not_found));
    }

    /**
     * Refuses to issue against a locked quote without a signature from the key it is locked to
     * (NUT-20).
     *
     * <p>An unlocked quote passes straight through, which is NUT-04's behaviour and the reason
     * NUT-20 exists: a quote id alone is a bearer token, and it travels through logs, webhooks and
     * traces, so anyone who reads one can take the ecash of a paid quote.
     *
     * <p>Checked before anything is signed, so a request that cannot mint leaves no trace of an
     * issuance behind it.
     */
    private void requireMintQuoteSignature(MintQuote quote,
                                           String quoteId,
                                           List<BlindedMessage> outputs) throws CashuErrorException {
        String pubkey = quote.pubkey();
        if (pubkey == null || pubkey.isBlank()) {
            return;
        }
        String signature = postMintRequest.getSignature();
        if (signature == null || signature.isBlank()) {
            log.warn("mint_task nut20_signature_missing quote_id={}", quoteId);
            throw new CashuErrorException(CashuErrorCode.pubkey_required_for_mint_quote);
        }
        if (!MintQuoteSignature.isValid(quoteId, outputs, pubkey, signature)) {
            log.warn("mint_task nut20_signature_invalid quote_id={}", quoteId);
            throw new CashuErrorException(CashuErrorCode.mint_signature_invalid);
        }
    }

    /**
     * Whether the payer has already paid for this quote.
     *
     * <p>An expiry bounds how long the payer has to pay an invoice, not how long the mint will
     * honour a payment it has already taken. NUT-04 makes the mintable amount
     * {@code amount_paid - amount_issued}; it does not make a paid quote unmintable once its
     * expiry passes. Rejecting one takes the customer's money and issues nothing, which is why
     * this is checked before {@code quote_expired} is raised rather than after.
     *
     * <p>Answers false when payment cannot be determined. That is the safe direction here: the
     * caller only consults this to suppress an expiry rejection, so an unknown state leaves the
     * existing behaviour untouched, and the real payment check further down still gates issuance.
     */
    private boolean alreadyPaid(Gateway gateway, String quoteId) {
        try {
            if (paymentStatusChecker != null && paymentStatusChecker.isPaid(quoteId)) {
                return true;
            }
            return gateway.checkPaymentStatus(quoteId);
        } catch (RuntimeException e) {
            log.warn("mint_task expiry_paid_check_failed quote_id={} reason={}", quoteId, e.getMessage());
            return false;
        }
    }


    /**
     * The voucher type whose face value is derived from a locked rate rather than collateral.
     * Matches what {@code VoucherMintQuoteTask} records when it persists the quote.
     */
    private static final String VOUCHER_TYPE_CUSTOMER_PAID = "customer_paid";

    /**
     * Checks that the customer paid what their own quote charged.
     *
     * <p>This is the invariant that actually applies to a rate-locked voucher. The face value is
     * a fiat figure derived from the locked rate; the sum the customer owes is
     * {@code charged_amount}, which for this variant is the fee. Comparing funding against the
     * face value instead compares two different quantities in two different scales.
     */
    private void enforceChargedAmountPaid(String quoteId, VoucherQuote quote, VoucherFunding funding)
            throws CashuErrorException {
        long owed = quote.chargedAmount();
        if (funding.amount() < owed || !java.util.Objects.equals(funding.unit(), quote.unit())) {
            log.warn("[voucher][alert] face_value_not_backed quote_id={} funding_source=CUSTOMER_PAYMENT "
                            + "reason=underpaid charged_amount={} funding_amount={} quote_unit={} funding_unit={}",
                    quoteId, owed, funding.amount(), quote.unit(), funding.unit());
            MetricRecorders.voucher().rejected(VoucherRejectionReason.FACE_VALUE_NOT_BACKED);
            throw new CashuErrorException(CashuErrorCode.face_value_not_backed);
        }
        log.info("voucher_backing customer_paid_accepted quote_id={} charged_amount={} funding_amount={} unit={}",
                quoteId, owed, funding.amount(), quote.unit());
    }

}
