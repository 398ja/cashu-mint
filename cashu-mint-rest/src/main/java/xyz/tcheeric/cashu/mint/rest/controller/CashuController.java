package xyz.tcheeric.cashu.mint.rest.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpClientErrorException;
import xyz.tcheeric.cashu.common.ActiveKeySet;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.HashToCurveSecret;
import xyz.tcheeric.cashu.common.KeySet;
import xyz.tcheeric.cashu.common.nut18.PaymentMethod;
import xyz.tcheeric.cashu.common.Proof;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.common.util.SecretUtil;
import xyz.tcheeric.cashu.entities.rest.ActiveKeySetResponse;
import xyz.tcheeric.cashu.entities.rest.ErrorResponse;
import xyz.tcheeric.cashu.entities.rest.KeySetResponse;
import xyz.tcheeric.cashu.entities.rest.nut03.PostSwapRequest;
import xyz.tcheeric.cashu.entities.rest.nut03.PostSwapResponse;
import xyz.tcheeric.cashu.entities.rest.nut04.PostMintQuoteRequest;
import xyz.tcheeric.cashu.entities.rest.nut04.PostMintQuoteResponse;
import xyz.tcheeric.cashu.entities.rest.nut04.PostMintRequest;
import xyz.tcheeric.cashu.entities.rest.nut04.PostMintResponse;
import xyz.tcheeric.cashu.entities.rest.nut05.PostMeltQuoteRequest;
import xyz.tcheeric.cashu.entities.rest.nut05.PostMeltQuoteResponse;
import xyz.tcheeric.cashu.entities.rest.nut05.PostMeltRequest;
import xyz.tcheeric.cashu.entities.rest.nut05.PostMeltResponse;
import xyz.tcheeric.cashu.entities.rest.nut07.PostCheckStateRequest;
import xyz.tcheeric.cashu.entities.rest.nut07.PostCheckStateResponse;
import xyz.tcheeric.cashu.entities.rest.nut09.PostRestoreRequest;
import xyz.tcheeric.cashu.entities.rest.nut09.PostRestoreResponse;
import xyz.tcheeric.cashu.common.nut17.QuoteStatePayload;
import xyz.tcheeric.cashu.mint.proto.nut.NUT02;
import xyz.tcheeric.cashu.mint.proto.nut.NUT03;
import xyz.tcheeric.cashu.mint.proto.nut.NUT04;
import xyz.tcheeric.cashu.mint.proto.nut.NUT05;
import xyz.tcheeric.cashu.mint.proto.nut.NUT06;
import xyz.tcheeric.cashu.mint.proto.nut.NUT07;
import xyz.tcheeric.cashu.mint.proto.nut.NUT09;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.SignatureVaultService;
import xyz.tcheeric.cashu.mint.proto.service.impl.MintProtocolServiceFactory;
import xyz.tcheeric.cashu.mint.proto.util.MintInfo;
import xyz.tcheeric.cashu.mint.rest.service.Nut17EventPublisher;
import xyz.tcheeric.payment.adapter.core.common.InvoiceNotPaidException;

import org.springframework.lang.Nullable;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/v1")
public class CashuController<T extends Secret> implements org.springframework.context.ApplicationEventPublisherAware {

    // Request ID header for tracing (matches gateway's AbstractRequestBase)
    public static final String REQUEST_ID_HEADER = "X-Request-ID";

    // Spec 036 — trace producer seam. Spring injects this via the aware callback
    // (no constructor change). Trace application events are published here at
    // post-success seams; the @Async TraceMintProducer turns them into signed
    // kind-9079 events. When tracing is disabled there is no listener, so these
    // publishes are no-ops and mint behaviour is unchanged.
    private org.springframework.context.ApplicationEventPublisher applicationEventPublisher;

    @Override
    public void setApplicationEventPublisher(org.springframework.context.ApplicationEventPublisher publisher) {
        this.applicationEventPublisher = publisher;
    }

    private final NUT06 nut06;
    private final MintLoadService mintLoadService;
    private final SignatureVaultService signatureVaultService;
    @Nullable
    private final Nut17EventPublisher eventPublisher;
    // Spec 002: vault services are injected as Spring beans so ITs can
    // @MockBean them. Previously the melt path instantiated DefaultXxx
    // directly, which forced ITs to spin up a real cashu-vault.
    private final xyz.tcheeric.cashu.mint.proto.service.MintVaultService mintVaultService;
    private final xyz.tcheeric.cashu.mint.proto.service.ProofVaultService proofVaultService;

    public CashuController(NUT06 nut06,
                           MintLoadService mintLoadService,
                           SignatureVaultService signatureVaultService,
                           @Nullable Nut17EventPublisher eventPublisher,
                           xyz.tcheeric.cashu.mint.proto.service.MintVaultService mintVaultService,
                           xyz.tcheeric.cashu.mint.proto.service.ProofVaultService proofVaultService) {
        this.nut06 = nut06;
        this.mintLoadService = mintLoadService;
        this.signatureVaultService = signatureVaultService;
        this.eventPublisher = eventPublisher;
        this.mintVaultService = mintVaultService;
        this.proofVaultService = proofVaultService;
    }

    // Keyset generation is an administrative operation and not part of the public spec.

    @GetMapping({"/keys/keyset/{keyset_id}", "/keys/{keyset_id}"})
    public ResponseEntity<KeySetResponse> keyset(@PathVariable("keyset_id") String keysetId) throws CashuErrorException {
        log.debug("keys({})", keysetId);
        KeySet keySet = NUT02.keys(keysetId, mintLoadService);
        KeySetResponse response = new KeySetResponse(List.of(keySet));
        return ResponseEntity.ok(response);
    }

    /**
     * Spec 041 Phase 1 — NUT-01 bare keys listing.
     *
     * <p>Returns one {@link KeySet} per currently-active keyset. Required by
     * {@code @cashu/cashu-ts} ({@code Wallet.loadMint()} calls
     * {@code GET /v1/keys} as the bootstrap step). Prior to this, the mint
     * exposed only the NUT-02 two-step pattern
     * ({@code /v1/keysets} + {@code /v1/keys/{id}}), which cashu-ts
     * 4.x clients cannot consume.
     *
     * <p>Implementation: aggregate the per-keyset NUT-02 lookups instead of
     * touching the inner protocol layer — keeps blast radius minimal and
     * reuses the proven {@code LoadKeySetTask} path.
     */
    @GetMapping("/keys")
    public ResponseEntity<KeySetResponse> allActiveKeys() throws CashuErrorException {
        List<ActiveKeySet> active = NUT02.activeKeySets(mintLoadService);
        List<KeySet> keysets = new java.util.ArrayList<>(active.size());
        for (ActiveKeySet aks : active) {
            keysets.add(NUT02.keys(aks.getId(), mintLoadService));
        }
        log.debug("keys() returned {} active keysets", keysets.size());
        return ResponseEntity.ok(new KeySetResponse(keysets));
    }

    @GetMapping("/keysets")
    public ResponseEntity<ActiveKeySetResponse> keysets() throws CashuErrorException {
        List<ActiveKeySet> activeKeySets = NUT02.activeKeySets(mintLoadService);
        ActiveKeySetResponse response = new ActiveKeySetResponse(activeKeySets);
        return ResponseEntity.ok(response);
    }

    // Note: No /keys/active route – not part of NUT-02. Use /keysets.

    // Spec-compliant: infer mint from inputs' keyset id
    @PostMapping("/swap")
    public ResponseEntity<PostSwapResponse> swap(@RequestBody PostSwapRequest<T> request,
                                                 HttpServletRequest httpRequest) throws CashuErrorException {
        // Extract request ID for tracing duplicate requests
        String requestId = httpRequest.getHeader(REQUEST_ID_HEADER);
        long startTime = System.currentTimeMillis();

        if (request.getInputs() == null || request.getInputs().isEmpty()) {
            log.warn("swap_controller bad_request request_id={} reason=empty_inputs", requestId);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        log.info("swap_controller request_received request_id={} input_count={} output_count={}",
                requestId, request.getInputs().size(),
                request.getBlindedMessages() != null ? request.getBlindedMessages().size() : 0);

        UUID mintId = inferMintIdFromSwapInputs(request);
        if (mintId == null) {
            log.warn("swap_controller bad_request request_id={} reason=mint_not_found", requestId);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        log.debug("swap_controller mint_resolved request_id={} mint_id={}", requestId, mintId);

        try {
            PostSwapResponse response = NUT03.swap(mintId, request, mintLoadService, signatureVaultService);
            long duration = System.currentTimeMillis() - startTime;

            if (response == null) {
                log.warn("swap_controller swap_returned_null request_id={} duration_ms={}", requestId, duration);
                return ResponseEntity.notFound().build();
            }

            log.info("swap_controller swap_completed request_id={} duration_ms={} signature_count={}",
                    requestId, duration, response.getBlindSignatures() != null ? response.getBlindSignatures().size() : 0);

            // Publish proof spent events for NUT-17 WebSocket subscribers
            if (eventPublisher != null) {
                eventPublisher.publishProofsSpent(request.getInputs());
            }

            return ResponseEntity.ok(response);
        } catch (CashuErrorException e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("swap_controller swap_failed request_id={} duration_ms={} error={}",
                    requestId, duration, e.getMessage());
            throw e;
        }
    }

    @PostMapping("/mint/quote/{method}")
    public ResponseEntity<PostMintQuoteResponse> quoteMint(@RequestBody PostMintQuoteRequest request,
                                                           @PathVariable("method") String method) throws CashuErrorException {
        var response = NUT04.quote(request.getAmount(), PaymentMethod.valueOf(method.toUpperCase()));
        publishTraceMintQuoteRequested(response);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/mint/quote/{method}/{quote_id}")
    public ResponseEntity<PostMintQuoteResponse> quoteMint(@PathVariable("method") String method,
                                                           @PathVariable("quote_id") String quoteId) throws CashuErrorException {
        PostMintQuoteResponse response = NUT04.quotePaymentStatus(quoteId, PaymentMethod.valueOf(method.toUpperCase()));
        return response == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(response);
    }

    /**
     * Create a voucher mint quote with percentage-based fee.
     *
     * <p>Unlike regular mint quotes that charge the full face value, voucher quotes
     * charge only a percentage (configured via voucher.quote.fee-percent, default 10%).
     *
     * <p>Example: With 10% fee, a 1000 sat voucher creates a 100 sat invoice.
     * After payment, the user can mint 1000 sat worth of tokens.
     *
     * @param request the mint quote request with voucher face value
     * @param method  the payment method (e.g., "bolt11")
     * @return the mint quote response with fee-based invoice
     */
    @PostMapping("/mint/quote/voucher/{method}")
    public ResponseEntity<PostMintQuoteResponse> quoteVoucherMint(@RequestBody PostMintQuoteRequest request,
                                                                  @PathVariable("method") String method) throws CashuErrorException {
        var response = NUT04.quoteVoucher(request.getAmount(), PaymentMethod.valueOf(method.toUpperCase()));
        return ResponseEntity.ok(response);
    }

    /**
     * Check payment status for a voucher mint quote.
     *
     * @param method  the payment method (e.g., "bolt11")
     * @param quoteId the quote identifier
     * @return the quote status response
     */
    @GetMapping("/mint/quote/voucher/{method}/{quote_id}")
    public ResponseEntity<PostMintQuoteResponse> quoteVoucherMint(@PathVariable("method") String method,
                                                                  @PathVariable("quote_id") String quoteId) throws CashuErrorException {
        PostMintQuoteResponse response = NUT04.voucherQuotePaymentStatus(quoteId, PaymentMethod.valueOf(method.toUpperCase()));
        return response == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(response);
    }

    /**
     * Compatibility helper for unit tests in admin project that directly call controller.mint(...).
     * Not exposed as a REST endpoint.
     */
    @Deprecated
    public ResponseEntity<PostMintResponse> mint(PostMintRequest<T> request,
                                                 String method,
                                                 String mintId) throws CashuErrorException {
        PostMintResponse response = NUT04.mint(UUID.fromString(mintId), request, PaymentMethod.valueOf(method.toUpperCase()), signatureVaultService);
        return response == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(response);
    }

    // NUT-04: POST /mint/{method} with quote and outputs in body
    @PostMapping("/mint/{method}")
    public ResponseEntity<PostMintResponse> mint(@RequestBody PostMintRequest<T> request,
                                                         @PathVariable("method") String method) throws CashuErrorException {
        if (request.getQuoteId() == null || request.getQuoteId().isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        if (request.getBlindedMessages() == null || request.getBlindedMessages().isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        UUID mintId = inferMintIdFromMintOutputs(request);
        if (mintId == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        PaymentMethod paymentMethod = PaymentMethod.valueOf(method.toUpperCase());
        if (log.isDebugEnabled()) {
            log.debug("Delegating mint: mintId={} method={} quoteId={}", mintId, paymentMethod, request.getQuoteId());
        }
        PostMintResponse response;
        try {
            response = NUT04.mint(
                    mintId,
                    request,
                    paymentMethod,
                    null, // unit resolved by service
                    mintLoadService, // use injected loader (preload in dev)
                    MintProtocolServiceFactory.getInstance(),
                    signatureVaultService
            );
        } catch (CashuErrorException e) {
            // Spec 036 — emit MINT_FAILED for the unpaid/invalid-invoice rejection,
            // then rethrow unchanged so the existing @ExceptionHandler produces the
            // same HTTP response (FR-014). Other reject codes are not traced here.
            if (isInvoiceNotPaid(e.getMessage())) {
                publishTraceMintFailed(request, "mint_invoice_not_paid_error", e.getMessage());
            }
            throw e;
        } catch (InvoiceNotPaidException e) {
            publishTraceMintFailed(request, "mint_invoice_not_paid_error", e.getMessage());
            throw e;
        } catch (HttpClientErrorException.NotFound e) {
            publishTraceMintFailed(request, "mint_invoice_not_paid_error", e.getMessage());
            throw e;
        }

        // Publish mint quote state change for NUT-17 WebSocket subscribers
        if (response != null && eventPublisher != null) {
            QuoteStatePayload payload = new QuoteStatePayload();
            payload.setQuoteId(request.getQuoteId());
            payload.setState("ISSUED");
            payload.setPaid(true);

            // Calculate total output amount from blinded messages
            long totalAmount = request.getBlindedMessages().stream()
                    .mapToLong(BlindedMessage::getAmount)
                    .sum();
            payload.setAmount(totalAmount);

            // Enrich with request and expiry from quote lookup
            try {
                PostMintQuoteResponse quoteStatus = NUT04.quotePaymentStatus(
                        request.getQuoteId(), paymentMethod);
                if (quoteStatus != null) {
                    payload.setRequest(quoteStatus.getRequest());
                    payload.setExpiry((long) quoteStatus.getExpiry());
                }
            } catch (Exception e) {
                log.debug("mint_quote_enrichment_skipped quote_id={} reason={}",
                        request.getQuoteId(), e.getMessage());
            }

            eventPublisher.publishMintQuoteState(request.getQuoteId(), payload);
        }

        return response == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(response);
    }

    @PostMapping("/melt/quote/{method}")
    public ResponseEntity<PostMeltQuoteResponse> quoteMelt(@RequestBody PostMeltQuoteRequest request,
                                                           @PathVariable("method") String method) {
        PostMeltQuoteResponse response = NUT05.quote(request, PaymentMethod.valueOf(method.toUpperCase()));
        publishTraceMeltQuoteRequested(request, response);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/melt/quote/{method}/{quote_id}")
    public ResponseEntity<PostMeltQuoteResponse> quoteMelt(@PathVariable("method") String method,
                                                           @PathVariable("quote_id") String quoteId) {
        PostMeltQuoteResponse response = NUT05.quotePaymentStatus(quoteId, PaymentMethod.valueOf(method.toUpperCase()));
        return response == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(response);
    }

    // Removed non-compliant by-mint variant; use /melt/{method}

    // NUT-05: POST /melt/{method} with quote and inputs in body
    @PostMapping("/melt/{method}")
    public ResponseEntity<PostMeltResponse> melt(@RequestBody PostMeltRequest<T> request,
                                                         @PathVariable("method") String method) throws CashuErrorException {
        if (request.getQuoteId() == null || request.getQuoteId().isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        if (request.getInputs() == null || request.getInputs().isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        UUID mintId = inferMintIdFromProofs(request.getInputs());
        if (mintId == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        PaymentMethod paymentMethod = PaymentMethod.valueOf(method.toUpperCase());
        PostMeltResponse response;
        try {
            response = NUT05.melt(
                    mintId,
                    request,
                    paymentMethod,
                    null,
                    MintProtocolServiceFactory.getInstance(),
                    mintLoadService,
                    mintVaultService,
                    proofVaultService,
                    signatureVaultService
            );
        } catch (CashuErrorException e) {
            // Spec 036 — emit MELT_FAILED only for the payment-failure/refund path
            // (MeltTask.refundAfterFailure throws melt_invoice_not_paid_error after
            // releasing the proofs), carrying the released inputs the controller
            // already holds. Then rethrow unchanged (FR-014). Validation rejects
            // and the parked-unknown path (payment_unknown) are not traced here.
            if (isMeltInvoiceNotPaid(e.getMessage())) {
                publishTraceMeltFailed(request, "melt_invoice_not_paid_error", e.getMessage());
            }
            throw e;
        }

        // Publish events for NUT-17 WebSocket subscribers
        if (response != null && eventPublisher != null) {
            // Publish proof spent events for input proofs
            eventPublisher.publishProofsSpent(request.getInputs());

            // Publish melt quote state change with enriched payload
            QuoteStatePayload payload = new QuoteStatePayload();
            payload.setQuoteId(request.getQuoteId());
            payload.setState(response.isPaid() ? "PAID" : "PENDING");
            payload.setPaid(response.isPaid());

            // Calculate total input amount from proofs
            long totalInputAmount = request.getInputs().stream()
                    .mapToLong(Proof::getAmount)
                    .sum();
            payload.setAmount(totalInputAmount);

            // Enrich with expiry from quote lookup
            try {
                PostMeltQuoteResponse quoteStatus = NUT05.quotePaymentStatus(
                        request.getQuoteId(), paymentMethod);
                if (quoteStatus != null) {
                    payload.setExpiry((long) quoteStatus.getExpiry());
                }
            } catch (Exception e) {
                log.debug("melt_quote_enrichment_skipped quote_id={} reason={}",
                        request.getQuoteId(), e.getMessage());
            }

            eventPublisher.publishMeltQuoteState(request.getQuoteId(), payload);
        }

        return response == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(response);
    }

    /**
     * Compatibility helper for unit tests in admin project that directly call controller.melt(...).
     * Not exposed as a REST endpoint.
     */
    @Deprecated
    public ResponseEntity<PostMeltResponse> melt(PostMeltRequest<T> request,
                                                 String method,
                                                 String mintId) throws CashuErrorException {
        PaymentMethod paymentMethod = PaymentMethod.valueOf(method.toUpperCase());
        PostMeltResponse response = NUT05.melt(
                UUID.fromString(mintId),
                request,
                paymentMethod,
                null,
                MintProtocolServiceFactory.getInstance(),
                mintLoadService,
                mintVaultService,
                proofVaultService
        );
        return response == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(response);
    }

    @GetMapping("/info")
    public ResponseEntity<ObjectNode> info() throws CashuErrorException {
        MintInfo info = nut06.mintInfo();
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode node = mapper.valueToTree(info);
        // Re-serialize nuts using getter to include dynamically loaded NUT-17
        node.set("nuts", mapper.valueToTree(info.getNuts()));
        addLegacyInfoFields(node, info);
        return ResponseEntity.ok(node);
    }

    // Spec-compliant: /v1/checkstate without mint id; infer or merge across mints
    @PostMapping("/checkstate")
    public ResponseEntity<PostCheckStateResponse> checkstate(@RequestBody PostCheckStateRequest request) throws CashuErrorException {
        return ResponseEntity.ok(mergeCheckStates(request));
    }

    @PostMapping("/restore")
    public ResponseEntity<PostRestoreResponse> restore(@RequestBody PostRestoreRequest request) throws CashuErrorException {
        PostRestoreResponse response = NUT09.restore(request, signatureVaultService);
        return ResponseEntity.ok(response);
    }

    // ---- Spec 036 trace producer seams ----

    /**
     * Publish a MINT_QUOTE_REQUESTED trace application event after a mint quote
     * is created. No-op when no publisher/listener is wired (tracing disabled).
     * The response already reflects the durably-created quote, so this fires
     * only for committed quotes (FR-006).
     */
    private void publishTraceMintQuoteRequested(PostMintQuoteResponse response) {
        if (applicationEventPublisher == null || response == null) {
            return;
        }
        applicationEventPublisher.publishEvent(new xyz.tcheeric.cashu.mint.rest.event.TraceMintQuoteRequestedEvent(
                this,
                response.getQuoteId(),
                response.getRequest(),
                null,
                response.getAmount(),
                response.getUnit(),
                response.getExpiry(),
                java.time.Instant.now()));
    }

    /**
     * Publish a MELT_QUOTE_REQUESTED trace application event after a melt quote
     * is created. The melt response carries amount/feeReserve/expiry; the bolt11
     * request comes from the request body.
     */
    private void publishTraceMeltQuoteRequested(PostMeltQuoteRequest request, PostMeltQuoteResponse response) {
        if (applicationEventPublisher == null || response == null) {
            return;
        }
        String bolt11 = request == null ? null : request.getRequest();
        applicationEventPublisher.publishEvent(new xyz.tcheeric.cashu.mint.rest.event.TraceMeltQuoteRequestedEvent(
                this,
                response.getQuoteId(),
                bolt11,
                response.getAmount(),
                response.getFeeReserve(),
                null,
                response.getExpiry(),
                java.time.Instant.now()));
    }

    /**
     * Publish a MINT_FAILED trace event after an unpaid/invalid issuance attempt
     * is rejected. No inputs/outputs; the amount is the sum of the requested
     * blinded-message amounts (what the wallet attempted to mint).
     */
    private void publishTraceMintFailed(PostMintRequest<T> request, String errorCode, String errorMessage) {
        if (applicationEventPublisher == null || request == null) {
            return;
        }
        long amount = 0L;
        if (request.getBlindedMessages() != null) {
            amount = request.getBlindedMessages().stream()
                    .mapToLong(BlindedMessage::getAmount)
                    .sum();
        }
        applicationEventPublisher.publishEvent(new xyz.tcheeric.cashu.mint.rest.event.TraceMintFailedEvent(
                this,
                request.getQuoteId(),
                amount,
                null,
                errorCode,
                errorMessage,
                java.time.Instant.now()));
    }

    /**
     * Publish a MELT_FAILED trace event after a melt payment fails and the input
     * proofs are released. The released inputs are the request's input proofs;
     * each is carried by its public Y only (never the plaintext secret).
     */
    private void publishTraceMeltFailed(PostMeltRequest<T> request, String errorCode, String errorMessage) {
        if (applicationEventPublisher == null || request == null || request.getInputs() == null) {
            return;
        }
        java.util.List<xyz.tcheeric.cashu.mint.rest.event.TraceProofInput> inputs = new java.util.ArrayList<>();
        long amount = 0L;
        for (Proof<T> proof : request.getInputs()) {
            if (proof == null) {
                continue;
            }
            amount += proof.getAmount();
            inputs.add(new xyz.tcheeric.cashu.mint.rest.event.TraceProofInput(
                    proof.getAmount(),
                    proof.getKeySetId(),
                    proof.getSecret() != null ? SecretUtil.toY(proof.getSecret()) : null));
        }
        applicationEventPublisher.publishEvent(new xyz.tcheeric.cashu.mint.rest.event.TraceMeltFailedEvent(
                this,
                request.getQuoteId(),
                amount,
                null,
                inputs,
                errorCode,
                errorMessage,
                java.time.Instant.now()));
    }

    /** True when the error payload carries the NUT-04 unpaid-invoice code. */
    private static boolean isInvoiceNotPaid(String message) {
        return message != null && message.contains("mint_invoice_not_paid_error");
    }

    /** True when the error payload carries the NUT-05 melt payment-failure code. */
    private static boolean isMeltInvoiceNotPaid(String message) {
        return message != null && message.contains("melt_invoice_not_paid_error");
    }

    // ---- Helpers ----

    private UUID inferMintIdFromSwapInputs(PostSwapRequest<T> request) throws CashuErrorException {
        if (request.getInputs() == null || request.getInputs().isEmpty()) {
            return null;
        }
        return inferMintIdFromProofs(request.getInputs());
    }

    private UUID inferMintIdFromMintOutputs(PostMintRequest<T> request) throws CashuErrorException {
        for (BlindedMessage output : request.getBlindedMessages()) {
            if (output == null || output.getKeySetId() == null) {
                continue;
            }
            UUID mintId = findMintIdByKeysetId(output.getKeySetId().toString());
            if (mintId != null) {
                return mintId;
            }
        }
        return null;
    }

    private UUID inferMintIdFromProofs(List<Proof<T>> proofs) throws CashuErrorException {
        for (Proof<T> proof : proofs) {
            if (proof == null) {
                continue;
            }
            UUID mintId = findMintIdByKeysetId(proof.getKeySetId());
            if (mintId != null) {
                return mintId;
            }
        }
        return null;
    }

    private UUID findMintIdByKeysetId(String keysetId) throws CashuErrorException {
        if (keysetId == null || keysetId.isBlank()) {
            return null;
        }
        for (boolean archive : new boolean[]{false, true}) {
            log.debug("Searching for mint with keyset id {} in archive {}", keysetId, archive);
            log.debug("mintLoadService: {}", mintLoadService);
            java.util.List<xyz.tcheeric.cashu.common.Mint> mints = mintLoadService.load(archive);
            if (mints == null) {
                continue;
            }
            for (var mint : mints) {
                log.debug("Mint: {} ", mint);
                if (mint.getKeySets() != null && mint.getKeySets().stream().anyMatch(ks -> keysetId.equals(ks.getId()))) {
                    return UUID.fromString(mint.getId());
                }
            }
        }
        return null;
    }

    private void addLegacyInfoFields(ObjectNode node, MintInfo info) {
        java.util.LinkedHashSet<String> units = new java.util.LinkedHashSet<>();
        java.util.LinkedHashSet<String> mintMethods = new java.util.LinkedHashSet<>();
        java.util.LinkedHashSet<String> meltMethods = new java.util.LinkedHashSet<>();
        try {
            if (info.getNuts() != null) {
                var nut4 = info.getNuts().get("4");
                if (nut4 != null && nut4.getMethods() != null) {
                    for (var m : nut4.getMethods()) {
                        if (m.getUnit() != null && !m.getUnit().isBlank()) units.add(m.getUnit());
                        if (m.getMethod() != null && !m.getMethod().isBlank()) mintMethods.add(m.getMethod());
                    }
                }
                var nut5 = info.getNuts().get("5");
                if (nut5 != null && nut5.getMethods() != null) {
                    for (var m : nut5.getMethods()) {
                        if (m.getUnit() != null && !m.getUnit().isBlank()) units.add(m.getUnit());
                        if (m.getMethod() != null && !m.getMethod().isBlank()) meltMethods.add(m.getMethod());
                    }
                }
            }
        } catch (Exception ignore) { }
        var unitsArr = node.putArray("units");
        for (String u : units) unitsArr.add(u);
        var mintArr = node.putArray("mint_methods");
        for (String m : mintMethods) mintArr.add(m);
        var meltArr = node.putArray("melt_methods");
        for (String m : meltMethods) meltArr.add(m);
    }

    /**
     * Merges the check states of secrets across all mints (active and archived).
     * <p>
     * For each secret in the {@link PostCheckStateRequest}, this method:
     * <ul>
     *   <li>Collects state information from all active mints.</li>
     *   <li>If no state is found in active mints, checks archived mints.</li>
     *   <li>Prioritizes "spent" and "pending" states over "unspent".</li>
     *   <li>Builds a {@link PostCheckStateResponse} listing the most relevant state for each secret.</li>
     * </ul>
     * This ensures the response reflects the latest and most authoritative state for each secret,
     * even if the secret exists in multiple mints.
     *
     * @param request the request containing secrets to check
     * @return a response with the merged state for each secret
     * @throws CashuErrorException if an error occurs during state retrieval
     */
    private PostCheckStateResponse mergeCheckStates(PostCheckStateRequest request) throws CashuErrorException {
        java.util.Map<String, String> stateByKey = new java.util.LinkedHashMap<>();
        java.util.function.BiConsumer<String, String> merge = (k, s) -> {
            String prev = stateByKey.get(k);
            if (prev == null) stateByKey.put(k, s);
            else if (NUT07.SPENT.equals(s) || (NUT07.PENDING.equals(s) && NUT07.UNSPENT.equals(prev))) stateByKey.put(k, s);
        };
        // Active mints
        java.util.List<xyz.tcheeric.cashu.common.Mint> active = mintLoadService.load(false);
        if (active != null) {
            for (var m : active) mergeFromMintStates(merge, m, request);
        }
        // Archived if nothing
        if (stateByKey.isEmpty()) {
            java.util.List<xyz.tcheeric.cashu.common.Mint> archived = mintLoadService.load(true);
            if (archived != null) for (var m : archived) mergeFromMintStates(merge, m, request);
        }
        PostCheckStateResponse out = new PostCheckStateResponse();
        java.util.List<PostCheckStateResponse.ResponseState> list = new java.util.ArrayList<>();
        for (Map.Entry<String, String> e : stateByKey.entrySet()) {
            PostCheckStateResponse.ResponseState rs = new PostCheckStateResponse.ResponseState();
            rs.setHashToCurveSecret(HashToCurveSecret.fromString(e.getKey()));
            rs.setState(e.getValue());
            list.add(rs);
        }
        out.setStates(list);
        return out;
    }

    /**
     * Merges the check states for secrets from a single mint into the provided state map.
     * <p>
     * For each secret in the {@link PostCheckStateRequest}, this method:
     * <ul>
     *   <li>Retrieves the state from the given mint using {@link NUT07#checkState}.</li>
     *   <li>For each returned state, applies the merge logic to update the state map.</li>
     *   <li>Skips secrets or states that are null.</li>
     * </ul>
     * This helper is used by {@link #mergeCheckStates} to aggregate state information across mints.
     *
     * @param merge a function to merge a secret's state into the result map
     * @param mint the mint to query for secret states
     * @param request the request containing secrets to check
     * @throws CashuErrorException if an error occurs during state retrieval
     */
    private void mergeFromMintStates(java.util.function.BiConsumer<String, String> merge,
                                     xyz.tcheeric.cashu.common.Mint mint,
                                     PostCheckStateRequest request) throws CashuErrorException {
        var resp = NUT07.checkState(UUID.fromString(mint.getId()), request);
        if (resp == null || resp.getStates() == null) return;
        for (var st : resp.getStates()) {
            if (st.getHashToCurveSecret() == null || st.getState() == null) continue;
            merge.accept(st.getHashToCurveSecret().toString(), st.getState());
        }
    }

    @ExceptionHandler(CashuErrorException.class)
    public ResponseEntity<ErrorResponse> handleCashuError(CashuErrorException ex) {
        ObjectMapper mapper = new ObjectMapper();

        // Try to recover the original JSON from the exception's detailMessage
        String rawMessage = ex.getMessage();
        try {
            Field detailMessageField = Throwable.class.getDeclaredField("detailMessage");
            detailMessageField.setAccessible(true);
            Object value = detailMessageField.get(ex);
            if (value instanceof String s) {
                rawMessage = s;
            }
        } catch (Exception ignore) {
            // Fallbacks below will handle parsing if reflection is not allowed
        }

        ErrorResponse error;
        try {
            // Prefer parsing the recovered raw message
            error = mapper.readValue(rawMessage, ErrorResponse.class);
        } catch (Exception parsePrimary) {
            try {
                // If that failed, try parsing getMessage() directly in case it actually contains JSON
                error = mapper.readValue(ex.getMessage(), ErrorResponse.class);
            } catch (Exception parseFallback) {
                // Last resort: generic internal error payload
                error = new ErrorResponse("internal_error");
            }
        }

        // Decide status from the parsed error code first (typed contract),
        // then fall back to message hints, then a 500 default.
        HttpStatus status;
        String code = error.code() == null ? "" : error.code();
        switch (code) {
            case "insufficient_input":
            case "amount_mismatch":
            case "invalid_quote_amount":
            case "quote_amount_cross_check_failed":
            case "quote_already_issued":
            case "issuance_in_progress":
            // Spec 007 — deterministic output-shape validation failures
            // (MintTask.validateDenominations) plus the voucher face-value
            // output-sum mismatch are client errors, not server faults.
            case "invalid_output_amount":
            case "invalid_denominations":
            case "missing_keyset_id":
            case "mint_request_missing_outputs":
            case "mint_request_contains_null_output":
            case "mint_amount_mismatch":
                status = HttpStatus.BAD_REQUEST;
                break;
            case "quote_not_found":
                status = HttpStatus.NOT_FOUND;
                break;
            default:
                String message = ex.getMessage();
                String normalized = message == null ? "" : message.trim();
                if (normalized.equalsIgnoreCase("not found") || normalized.toLowerCase().contains("not found")) {
                    status = HttpStatus.NOT_FOUND;
                } else {
                    status = HttpStatus.INTERNAL_SERVER_ERROR;
                }
                break;
        }

        return new ResponseEntity<>(error, status);
    }

    // Map gateway 404 on payment lookup to a structured "invoice not paid" error per NUT-04
    @ExceptionHandler(HttpClientErrorException.NotFound.class)
    public ResponseEntity<ErrorResponse> handleGatewayNotFound(HttpClientErrorException.NotFound ex) {
        ErrorResponse error = new ErrorResponse("mint_invoice_not_paid_error");
        return new ResponseEntity<>(error, HttpStatus.PAYMENT_REQUIRED);
    }

    @ExceptionHandler(InvoiceNotPaidException.class)
    public ResponseEntity<ErrorResponse> handleInvoiceNotPaid(InvoiceNotPaidException ex) {
        ErrorResponse error = new ErrorResponse("mint_invoice_not_paid_error");
        return new ResponseEntity<>(error, HttpStatus.PAYMENT_REQUIRED);
    }
}
