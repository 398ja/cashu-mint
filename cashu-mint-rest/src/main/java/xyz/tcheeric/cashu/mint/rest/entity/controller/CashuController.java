package xyz.tcheeric.cashu.mint.rest.entity.controller;

import xyz.tcheeric.cashu.common.model.ActiveKeySet;
import xyz.tcheeric.cashu.common.model.KeySet;
import xyz.tcheeric.cashu.common.model.MintInformation;
import xyz.tcheeric.cashu.common.model.PaymentMethod;
import xyz.tcheeric.cashu.common.model.Secret;
import xyz.tcheeric.cashu.common.model.rest.ActiveKeySetResponse;
import xyz.tcheeric.cashu.common.model.rest.KeySetResponse;
import xyz.tcheeric.cashu.common.model.rest.PostCheckStateRequest;
import xyz.tcheeric.cashu.common.model.rest.PostCheckStateResponse;
import xyz.tcheeric.cashu.common.model.rest.PostMeltQuoteRequest;
import xyz.tcheeric.cashu.common.model.rest.PostMeltQuoteResponse;
import xyz.tcheeric.cashu.common.model.rest.PostMeltRequest;
import xyz.tcheeric.cashu.common.model.rest.PostMeltResponse;
import xyz.tcheeric.cashu.common.model.rest.PostMintQuoteRequest;
import xyz.tcheeric.cashu.common.model.rest.PostMintQuoteResponse;
import xyz.tcheeric.cashu.common.model.rest.PostMintRequest;
import xyz.tcheeric.cashu.common.model.rest.PostMintResponse;
import xyz.tcheeric.cashu.common.model.rest.PostSwapRequest;
import xyz.tcheeric.cashu.common.model.rest.PostSwapResponse;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.mint.proto.nut.NUT02;
import xyz.tcheeric.cashu.mint.proto.nut.NUT03;
import xyz.tcheeric.cashu.mint.proto.nut.NUT04;
import xyz.tcheeric.cashu.mint.proto.nut.NUT05;
import xyz.tcheeric.cashu.mint.proto.nut.NUT06;
import xyz.tcheeric.cashu.mint.proto.nut.NUT07;
import lombok.extern.java.Log;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.logging.Level;

@Log
@RestController
@RequestMapping(value = "/v1")
public class CashuController<T extends Secret> {

    @GetMapping("/keys")
    public ResponseEntity<KeySetResponse> keys() {
        log.log(Level.FINE, "Getting keys");
        KeySetResponse response = new KeySetResponse(NUT02.keys());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/keys/{keyset_id}")
    public ResponseEntity<KeySetResponse> keys(@PathVariable("keyset_id") String keysetId) {
        log.log(Level.FINE, "keys({0})", keysetId);
        KeySet keySet = NUT02.keys(keysetId);
        KeySetResponse response = new KeySetResponse(List.of(keySet));
        return ResponseEntity.ok(response);
    }

    // TODO
    @GetMapping("/keysets")
    public ResponseEntity<ActiveKeySetResponse> keysets() {
        List<ActiveKeySet> activeKeySets = NUT02.activeKeySets();
        ActiveKeySetResponse response = new ActiveKeySetResponse(activeKeySets);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/swap")
    public ResponseEntity<PostSwapResponse> swap(@RequestBody PostSwapRequest<T> request) throws CashuErrorException {
        PostSwapResponse response = NUT03.swap(request);
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

    @PostMapping("/mint/{method}")
    public ResponseEntity<PostMintResponse> mint(@RequestBody PostMintRequest<T> request, @PathVariable("method") String method) throws CashuErrorException {
        PostMintResponse response = NUT04.mint(request, PaymentMethod.valueOf(method.toUpperCase()));
        return response == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(response);
    }

    @PostMapping("/melt/quote/{method}")
    public ResponseEntity<PostMeltQuoteResponse> quoteMelt(@RequestBody PostMeltQuoteRequest request, @PathVariable("method") String method) {
        var response = NUT05.quote(request, PaymentMethod.valueOf(method.toUpperCase()));
        return ResponseEntity.ok(response);
    }

    @GetMapping("/melt/quote/{method}/{quote_id}")
    public ResponseEntity<PostMeltQuoteResponse> quoteMelt(@PathVariable("method") String method, @PathVariable("quote_id") String quoteId) {
        PostMeltQuoteResponse response = NUT05.quotePaymentStatus(quoteId, PaymentMethod.valueOf(method.toUpperCase()));
        return response == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(response);
    }

    @PostMapping("/melt/{method}")
    public ResponseEntity<PostMeltResponse> melt(@RequestBody PostMeltRequest<T> request, @PathVariable("method") String method) throws CashuErrorException {
        PostMeltResponse response = NUT05.melt(request, PaymentMethod.valueOf(method.toUpperCase()));
        return response == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(response);
    }

    @GetMapping("/info")
    public ResponseEntity<MintInformation> info() {
        MintInformation response = NUT06.info();
        return ResponseEntity.ok(response);
    }

    @PostMapping("/checkstate")
    public ResponseEntity<PostCheckStateResponse> checkstate(@RequestBody PostCheckStateRequest request) {
        PostCheckStateResponse response = NUT07.checkState(request);
        return ResponseEntity.ok(response);
    }

    @ExceptionHandler(CashuErrorException.class)
    public ResponseEntity<String> handleCashuError(CashuErrorException ex) {
        return new ResponseEntity<>(ex.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
