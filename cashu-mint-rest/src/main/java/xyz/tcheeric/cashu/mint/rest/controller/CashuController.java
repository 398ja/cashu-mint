package xyz.tcheeric.cashu.mint.rest.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
import xyz.tcheeric.cashu.common.PaymentMethod;
import xyz.tcheeric.cashu.common.Proof;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.rest.ActiveKeySetResponse;
import xyz.tcheeric.cashu.entities.rest.ErrorResponse;
import xyz.tcheeric.cashu.entities.rest.KeySetResponse;
import xyz.tcheeric.cashu.entities.rest.PostCheckStateRequest;
import xyz.tcheeric.cashu.entities.rest.PostCheckStateResponse;
import xyz.tcheeric.cashu.entities.rest.PostMeltQuoteRequest;
import xyz.tcheeric.cashu.entities.rest.PostMeltQuoteResponse;
import xyz.tcheeric.cashu.entities.rest.PostMeltRequest;
import xyz.tcheeric.cashu.entities.rest.PostMeltResponse;
import xyz.tcheeric.cashu.entities.rest.PostMintQuoteRequest;
import xyz.tcheeric.cashu.entities.rest.PostMintQuoteResponse;
import xyz.tcheeric.cashu.entities.rest.PostMintRequest;
import xyz.tcheeric.cashu.entities.rest.PostMintResponse;
import xyz.tcheeric.cashu.entities.rest.PostRestoreRequest;
import xyz.tcheeric.cashu.entities.rest.PostRestoreResponse;
import xyz.tcheeric.cashu.entities.rest.PostSwapRequest;
import xyz.tcheeric.cashu.entities.rest.PostSwapResponse;
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
import xyz.tcheeric.gateway.common.InvoiceNotPaidException;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/v1")
public class CashuController<T extends Secret> {

    private final NUT06 nut06;
    private final MintLoadService mintLoadService;
    private final SignatureVaultService signatureVaultService;

    public CashuController(NUT06 nut06, MintLoadService mintLoadService, SignatureVaultService signatureVaultService) {
        this.nut06 = nut06;
        this.mintLoadService = mintLoadService;
        this.signatureVaultService = signatureVaultService;
    }

    // Keyset generation is an administrative operation and not part of the public spec.

    @GetMapping({"/keys/keyset/{keyset_id}", "/keys/{keyset_id}"})
    public ResponseEntity<KeySetResponse> keyset(@PathVariable("keyset_id") String keysetId) throws CashuErrorException {
        log.debug("keys({})", keysetId);
        KeySet keySet = NUT02.keys(keysetId, mintLoadService);
        KeySetResponse response = new KeySetResponse(List.of(keySet));
        return ResponseEntity.ok(response);
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
    public ResponseEntity<PostSwapResponse> swap(@RequestBody PostSwapRequest<T> request) throws CashuErrorException {
        if (request.getInputs() == null || request.getInputs().isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        log.debug("Swapping {} inputs", request.getInputs().size());
        UUID mintId = inferMintIdFromSwapInputs(request);
        if (mintId == null) return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        log.debug("Found mint: {}", mintId);
        PostSwapResponse response = NUT03.swap(mintId, request, mintLoadService, signatureVaultService);
        return response == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(response);
    }

    @PostMapping("/mint/quote/{method}")
    public ResponseEntity<PostMintQuoteResponse> quoteMint(@RequestBody PostMintQuoteRequest request,
                                                           @PathVariable("method") String method) throws CashuErrorException {
        var response = NUT04.quote(request.getAmount(), PaymentMethod.valueOf(method.toUpperCase()));
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
        PostMintResponse response = NUT04.mint(
                mintId,
                request,
                paymentMethod,
                null, // unit resolved by service
                mintLoadService, // use injected loader (preload in dev)
                MintProtocolServiceFactory.getInstance(),
                signatureVaultService
        );
        return response == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(response);
    }

    @PostMapping("/melt/quote/{method}")
    public ResponseEntity<PostMeltQuoteResponse> quoteMelt(@RequestBody PostMeltQuoteRequest request,
                                                           @PathVariable("method") String method) {
        PostMeltQuoteResponse response = NUT05.quote(request, PaymentMethod.valueOf(method.toUpperCase()));
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
        PostMeltResponse response = NUT05.melt(
                mintId,
                request,
                paymentMethod,
                null,
                MintProtocolServiceFactory.getInstance(),
                mintLoadService,
                new xyz.tcheeric.cashu.mint.proto.service.impl.DefaultMintVaultService(),
                new xyz.tcheeric.cashu.mint.proto.service.impl.DefaultProofVaultService()
        );
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
                new xyz.tcheeric.cashu.mint.proto.service.impl.DefaultMintVaultService(),
                new xyz.tcheeric.cashu.mint.proto.service.impl.DefaultProofVaultService()
        );
        return response == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(response);
    }

    @GetMapping("/info")
    public ResponseEntity<ObjectNode> info() throws CashuErrorException {
        MintInfo info = nut06.mintInfo();
        ObjectNode node = new ObjectMapper().valueToTree(info);
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

        // Decide status using message hints; default to 500 for structured errors in this handler
        HttpStatus status;
        String message = ex.getMessage();
        String normalized = message == null ? "" : message.trim();
        if (normalized.equalsIgnoreCase("not found") || normalized.toLowerCase().contains("not found")) {
            status = HttpStatus.NOT_FOUND;
        } else {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
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
