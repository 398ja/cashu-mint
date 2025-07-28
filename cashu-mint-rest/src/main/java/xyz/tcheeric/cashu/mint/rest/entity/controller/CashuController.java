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
import xyz.tcheeric.cashu.common.ActiveKeySet;
import xyz.tcheeric.cashu.common.KeySet;
import xyz.tcheeric.cashu.common.PaymentMethod;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.rest.ActiveKeySetResponse;
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
import xyz.tcheeric.cashu.entities.rest.PostSwapRequest;
import xyz.tcheeric.cashu.entities.rest.PostSwapResponse;
import xyz.tcheeric.cashu.mint.proto.nut.NUT02;
import xyz.tcheeric.cashu.mint.proto.nut.NUT03;
import xyz.tcheeric.cashu.mint.proto.nut.NUT04;
import xyz.tcheeric.cashu.mint.proto.nut.NUT05;
import xyz.tcheeric.cashu.mint.proto.nut.NUT06;
import xyz.tcheeric.cashu.mint.proto.nut.NUT07;
import xyz.tcheeric.cashu.mint.proto.service.DefaultMintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.mint.proto.util.MintInfo;

import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping(value = "/v1")
public class CashuController<T extends Secret> {

    private final NUT06 nut06;

    public CashuController(NUT06 nut06) {
        this.nut06 = nut06;
    }

    @GetMapping("/keys/{mint_id}/generate")
    public ResponseEntity<KeySetResponse> generateKeySetIds(@PathVariable("mint_id") String mintId) throws CashuErrorException {
        log.debug("Getting keys");
        KeySetResponse response = new KeySetResponse(NUT02.keys(UUID.fromString(mintId)));
        return ResponseEntity.ok(response);
    }

    @GetMapping("/keys/keyset/{keyset_id}")
    public ResponseEntity<KeySetResponse> keyset(@PathVariable("keyset_id") String keysetId) throws CashuErrorException {
        log.debug("keys({})", keysetId);
        KeySet keySet = NUT02.keys(keysetId, new DefaultMintLoadService());
        KeySetResponse response = new KeySetResponse(List.of(keySet));
        return ResponseEntity.ok(response);
    }

    // TODO
    @GetMapping("/keysets")
    public ResponseEntity<ActiveKeySetResponse> keysets(MintLoadService mintLoadService) throws CashuErrorException {
        List<ActiveKeySet> activeKeySets = NUT02.activeKeySets(mintLoadService);
        ActiveKeySetResponse response = new ActiveKeySetResponse(activeKeySets);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/swap/{mint_id}")
    public ResponseEntity<PostSwapResponse> swap(@PathVariable("mint_id") String mintId, @RequestBody PostSwapRequest<T> request) throws CashuErrorException {
        PostSwapResponse response = NUT03.swap(UUID.fromString(mintId), request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/mint/quote/{method}")
    public ResponseEntity<PostMintQuoteResponse> quoteMint(@RequestBody PostMintQuoteRequest request, @PathVariable("method") String method) {
        var response = NUT04.quote(request.getAmount(), PaymentMethod.valueOf(method.toUpperCase()));
        return ResponseEntity.ok(response);
    }

    @GetMapping("/mint/quote/{method}/{quote_id}")
    public ResponseEntity<PostMintQuoteResponse> quoteMint(@PathVariable("method") String method, @PathVariable("quote_id") String quoteId) {
        PostMintQuoteResponse response = NUT04.quotePaymentStatus(quoteId, PaymentMethod.valueOf(method.toUpperCase()));
        return response == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(response);
    }

    @PostMapping("/mint/{mintId}/{method}")
    public ResponseEntity<PostMintResponse> mint(@RequestBody PostMintRequest<T> request, @PathVariable("method") String method, @PathVariable("mintId") String mintId) throws CashuErrorException {
        PostMintResponse response = NUT04.mint(UUID.fromString(mintId), request, PaymentMethod.valueOf(method.toUpperCase()));
        return response == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(response);
    }

    @PostMapping("/melt/quote/{method}")
    public ResponseEntity<PostMeltQuoteResponse> quoteMelt(@RequestBody PostMeltQuoteRequest request, @PathVariable("method") String method) {
        PostMeltQuoteResponse response = NUT05.quote(request, PaymentMethod.valueOf(method.toUpperCase()));
        return ResponseEntity.ok(response);
    }

    @GetMapping("/melt/quote/{method}/{quote_id}")
    public ResponseEntity<PostMeltQuoteResponse> quoteMelt(@PathVariable("method") String method, @PathVariable("quote_id") String quoteId) {
        PostMeltQuoteResponse response = NUT05.quotePaymentStatus(quoteId, PaymentMethod.valueOf(method.toUpperCase()));
        return response == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(response);
    }

    @PostMapping("/melt/{mint_id}/{method}")
    public ResponseEntity<PostMeltResponse> melt(@RequestBody PostMeltRequest<T> request, @PathVariable("method") String method, @PathVariable("mint_id") String mintId) throws CashuErrorException {
        PostMeltResponse response = NUT05.melt(UUID.fromString(mintId), request, PaymentMethod.valueOf(method.toUpperCase()));
        return response == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(response);
    }

    @GetMapping("/info")
    public ResponseEntity<MintInfo> info() {
        MintInfo response = nut06.mintInfo();
        return ResponseEntity.ok(response);
    }

    @PostMapping("/checkstate/{mint_id}")
    public ResponseEntity<PostCheckStateResponse> checkstate(@RequestBody PostCheckStateRequest request, @PathVariable("mint_id") String mintId) throws CashuErrorException {
        PostCheckStateResponse response = NUT07.checkState(UUID.fromString(mintId), request);
        return ResponseEntity.ok(response);
    }

    @ExceptionHandler(CashuErrorException.class)
    public ResponseEntity<String> handleCashuError(CashuErrorException ex) {
        return new ResponseEntity<>(ex.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
