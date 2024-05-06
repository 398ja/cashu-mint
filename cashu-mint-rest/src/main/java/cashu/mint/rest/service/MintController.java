package cashu.mint.rest.service;

import cashu.common.error.CashuException;
import cashu.common.model.ActiveKeySet;
import cashu.common.model.KeySet;
import cashu.common.model.MintInformation;
import cashu.common.model.PaymentMethod;
import cashu.common.model.rest.PostMeltQuoteResponse;
import cashu.common.model.rest.PostMeltRequest;
import cashu.common.model.rest.PostMeltResponse;
import cashu.common.model.rest.PostMintQuoteRequest;
import cashu.common.model.rest.PostMintQuoteResponse;
import cashu.common.model.rest.PostMintRequest;
import cashu.common.model.rest.PostMintResponse;
import cashu.common.model.rest.PostSwapRequest;
import cashu.common.model.rest.PostSwapResponse;
import cashu.mint.actor.Mint;
import cashu.mint.nut.NUT02;
import cashu.mint.nut.NUT03;
import cashu.mint.nut.NUT04;
import cashu.mint.nut.NUT05;
import cashu.mint.nut.NUT06;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController("/v1")
public class MintController {

    @GetMapping("/keys")
    public List<KeySet> keys() {
        return NUT02.keysets();
    }

    @GetMapping("/keysets")
    public List<ActiveKeySet> keysets() {
        return null;
    }

    @PostMapping("/swap")
    public PostSwapResponse swap(@RequestBody PostSwapRequest request) throws CashuException {
        return NUT03.swap(request, getMint());
    }

    @PostMapping("/mint/quote/{method}")
    public PostMintQuoteResponse quoteMint(@RequestBody PostMintQuoteRequest request, @PathVariable String method) {
        return NUT04.quote(request.getAmount(), PaymentMethod.valueOf(method.toUpperCase()));
    }

    @GetMapping("/mint/quote/{method}/{quote_id}")
    public PostMintQuoteResponse quoteMint(@PathVariable String method, @PathVariable("quote_id") String quoteId) {
        return NUT04.quotePaymentStatus(quoteId, PaymentMethod.valueOf(method.toUpperCase()));
    }

    @PostMapping("/mint/{method}")
    public PostMintResponse mint(@RequestBody PostMintRequest request, @PathVariable String method) {
        return NUT04.mint(request, PaymentMethod.valueOf(method.toUpperCase()), getMint());
    }

    @GetMapping("/melt/quote/{method}/{quote_id}")
    public PostMeltQuoteResponse quoteMelt(@PathVariable String method, @PathVariable("quote_id") String quoteId) {
        return NUT05.quotePaymentStatus(quoteId, PaymentMethod.valueOf(method.toUpperCase()));
    }

    @PostMapping("/melt/{method}")
    public PostMeltResponse melt(@RequestBody PostMeltRequest request, @PathVariable String method) {
        return NUT05.melt(request, PaymentMethod.valueOf(method.toUpperCase()), getMint());
    }

    @GetMapping("/info")
    public MintInformation info() {
        return NUT06.info();
    }

    private Mint getMint() {
        return null;
    }
}
