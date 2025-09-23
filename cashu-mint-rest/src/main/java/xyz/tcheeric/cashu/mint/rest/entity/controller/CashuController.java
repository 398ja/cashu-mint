package xyz.tcheeric.cashu.mint.rest.entity.controller;

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
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.web.client.HttpClientErrorException;
import xyz.tcheeric.cashu.common.ActiveKeySet;
import xyz.tcheeric.cashu.common.KeySet;
import xyz.tcheeric.cashu.common.PaymentMethod;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.rest.ActiveKeySetResponse;
import xyz.tcheeric.cashu.entities.rest.KeySetResponse;
import xyz.tcheeric.cashu.entities.rest.ErrorResponse;
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
import xyz.tcheeric.cashu.entities.rest.PostSwapRequest;
import xyz.tcheeric.cashu.entities.rest.PostSwapResponse;
import xyz.tcheeric.cashu.entities.rest.PostRestoreRequest;
import xyz.tcheeric.cashu.entities.rest.PostRestoreResponse;
import xyz.tcheeric.cashu.mint.proto.nut.NUT02;
import xyz.tcheeric.cashu.mint.proto.nut.NUT03;
import xyz.tcheeric.cashu.mint.proto.nut.NUT04;
import xyz.tcheeric.cashu.mint.proto.nut.NUT05;
import xyz.tcheeric.cashu.mint.proto.nut.NUT06;
import xyz.tcheeric.cashu.mint.proto.nut.NUT07;
import xyz.tcheeric.cashu.mint.proto.nut.NUT09;
import xyz.tcheeric.cashu.mint.proto.service.DefaultMintInfoService;
import xyz.tcheeric.cashu.mint.proto.service.DefaultMintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolServiceFactory;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.SignatureVaultService;
import xyz.tcheeric.cashu.mint.proto.util.MintInfo;

import java.util.List;
import java.lang.reflect.Field;
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
        UUID mintId = inferMintIdFromSwapInputs(request);
        if (mintId == null) return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        PostSwapResponse response = NUT03.swap(mintId, request, signatureVaultService);
        return response == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(response);
    }

    @PostMapping("/mint/quote/{method}")
    public ResponseEntity<PostMintQuoteResponse> quoteMint(@RequestBody PostMintQuoteRequest request,
                                                           @PathVariable("method") String method) {
        var response = NUT04.quote(request.getAmount(), PaymentMethod.valueOf(method.toUpperCase()));
        return ResponseEntity.ok(response);
    }

    @GetMapping("/mint/quote/{method}/{quote_id}")
    public ResponseEntity<PostMintQuoteResponse> quoteMint(@PathVariable("method") String method,
                                                           @PathVariable("quote_id") String quoteId) {
        PostMintQuoteResponse response = NUT04.quotePaymentStatus(quoteId, PaymentMethod.valueOf(method.toUpperCase()));
        return response == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(response);
    }

/*
    @PostMapping("/mint/by-mint/{mintId}/{method}")
    public ResponseEntity<PostMintResponse> mint(@RequestBody PostMintRequest<T> request,
                                                 @PathVariable("method") String method,
                                                 @PathVariable("mintId") String mintId) throws CashuErrorException {
        PostMintResponse response = NUT04.mint(UUID.fromString(mintId), request, PaymentMethod.valueOf(method.toUpperCase()), signatureVaultService);
        return response == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(response);
    }
*/

    // NUT-04: POST /mint/{method} with quote and outputs in body
    @PostMapping("/mint/{method}")
    public ResponseEntity<PostMintResponse> mintByMethod(@RequestBody PostMintRequest<T> request,
                                                         @PathVariable("method") String method) throws CashuErrorException {
        if (request.getQuoteId() == null || request.getQuoteId().isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        java.util.List<xyz.tcheeric.cashu.common.Mint> active = mintLoadService.load(false);
        if (log.isDebugEnabled()) {
            log.debug("POST /mint/{} quoteId={} activeMints={}",
                    method, request.getQuoteId(), active == null ? 0 : active.size());
        }
        if (active == null || active.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        if (active.size() > 1) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        UUID mintId = UUID.fromString(active.get(0).getId());
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
    public ResponseEntity<PostMeltResponse> meltByMethod(@RequestBody PostMeltRequest<T> request,
                                                         @PathVariable("method") String method) throws CashuErrorException {
        if (request.getQuoteId() == null || request.getQuoteId().isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        java.util.List<xyz.tcheeric.cashu.common.Mint> active = mintLoadService.load(false);
        if (active == null || active.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        if (active.size() > 1) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        UUID mintId = UUID.fromString(active.get(0).getId());
        PaymentMethod paymentMethod = PaymentMethod.valueOf(method.toUpperCase());
        PostMeltResponse response = NUT05.melt(mintId, request, paymentMethod);
        return response == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(response);
    }

    @GetMapping("/info")
    public ResponseEntity<ObjectNode> info() {
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
        String keysetId = request.getInputs().get(0).getKeySetId();
        if (keysetId == null || keysetId.isBlank()) return null;
        // Search active mints first, then archived if not found
        for (boolean archive : new boolean[]{false, true}) {
            java.util.List<xyz.tcheeric.cashu.common.Mint> mints = mintLoadService.load(archive);
            if (mints == null) continue;
            for (var m : mints) {
                if (m.getKeySets() != null && m.getKeySets().stream().anyMatch(ks -> keysetId.equals(ks.getId()))) {
                    return UUID.fromString(m.getId());
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
        for (var e : stateByKey.entrySet()) {
            var rs = new PostCheckStateResponse.ResponseState();
            rs.setHashToCurveSecret(xyz.tcheeric.cashu.common.PublicKey.fromString(e.getKey()));
            rs.setState(e.getValue());
            list.add(rs);
        }
        out.setStates(list);
        return out;
    }

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
            if (value instanceof String s && s != null) {
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
}
