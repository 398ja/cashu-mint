package cashu.mint.rest.entity.controller;

import cashu.common.model.ActiveKeySet;
import cashu.common.model.KeySet;
import cashu.common.model.MintInformation;
import cashu.common.model.PaymentMethod;
import cashu.common.model.rest.ActiveKeySetResponse;
import cashu.common.model.rest.KeySetResponse;
import cashu.common.model.rest.PostMeltQuoteRequest;
import cashu.common.model.rest.PostMeltQuoteResponse;
import cashu.common.model.rest.PostMeltRequest;
import cashu.common.model.rest.PostMeltResponse;
import cashu.common.model.rest.PostMintQuoteRequest;
import cashu.common.model.rest.PostMintQuoteResponse;
import cashu.common.model.rest.PostMintRequest;
import cashu.common.model.rest.PostMintResponse;
import cashu.common.model.rest.PostSwapRequest;
import cashu.common.model.rest.PostSwapResponse;
import cashu.mint.nut.NUT02;
import cashu.mint.nut.NUT03;
import cashu.mint.nut.NUT04;
import cashu.mint.nut.NUT05;
import cashu.mint.nut.NUT06;
import cashu.mint.rest.client.MeltQuoteClient;
import cashu.mint.rest.client.MintQuoteClient;
import cashu.mint.rest.entity.MeltQuote;
import cashu.mint.rest.entity.MintQuote;
import lombok.extern.java.Log;
import org.springframework.http.ResponseEntity;
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
public class MintController {

    @GetMapping("/keys")
    public KeySetResponse keys() {
        log.log(Level.INFO, "Getting keys");
        return new KeySetResponse(NUT02.keys());
    }

    @GetMapping("/keys/{keyset_id}")
    public KeySetResponse keys(@PathVariable("keyset_id") String keysetId) {
        log.log(Level.INFO, "keys({0})", keysetId);
        KeySet keySet = NUT02.keys(keysetId);
        return new KeySetResponse(List.of(keySet));
    }

    // TODO
    @GetMapping("/keysets")
    public ActiveKeySetResponse keysets() {
        List<ActiveKeySet> activeKeySets = NUT02.activeKeySets();
        return new ActiveKeySetResponse(activeKeySets);
    }

    @PostMapping("/swap")
    public PostSwapResponse swap(@RequestBody PostSwapRequest request) {
        return NUT03.swap(request);
    }

    @PostMapping("/mint/quote/{method}")
    public PostMintQuoteResponse quoteMint(@RequestBody PostMintQuoteRequest request, @PathVariable("method") String method) {
        var quote = NUT04.quote(request.getAmount(), PaymentMethod.valueOf(method.toUpperCase()));

        MintQuoteClient client = new MintQuoteClient();
        client.createQuote(MintQuote.fromEntity(quote));

        return quote;
    }

    @GetMapping("/mint/quote/{method}/{quote_id}")
    public ResponseEntity<PostMintQuoteResponse> quoteMint(@PathVariable("method") String method, @PathVariable("quote_id") String quoteId) {
        PostMintQuoteResponse response = NUT04.quotePaymentStatus(quoteId, PaymentMethod.valueOf(method.toUpperCase()));
        return response == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(response);
    }

    @PostMapping("/mint/{method}")
    public ResponseEntity<PostMintResponse> mint(@RequestBody PostMintRequest request, @PathVariable("method") String method) {
        PostMintResponse response = NUT04.mint(request, PaymentMethod.valueOf(method.toUpperCase()));
        return response == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(response);
    }

    @PostMapping("/melt/quote/{method}")
    public PostMeltQuoteResponse quoteMelt(@RequestBody PostMeltQuoteRequest request, @PathVariable("method") String method) {
        var quote = NUT05.quote(request, PaymentMethod.valueOf(method.toUpperCase()));

        MeltQuoteClient client = new MeltQuoteClient();
        client.createQuote(MeltQuote.fromEntity(quote));

        return quote;
    }

    @GetMapping("/melt/quote/{method}/{quote_id}")
    public PostMeltQuoteResponse quoteMelt(@PathVariable("method") String method, @PathVariable("quote_id") String quoteId) {
        return NUT05.quotePaymentStatus(quoteId, PaymentMethod.valueOf(method.toUpperCase()));
    }

    @PostMapping("/melt/{method}")
    public PostMeltResponse melt(@RequestBody PostMeltRequest request, @PathVariable("method") String method) {
        return NUT05.melt(request, PaymentMethod.valueOf(method.toUpperCase()));
    }

    @GetMapping("/info")
    public MintInformation info() {
        return NUT06.info();
    }
}
