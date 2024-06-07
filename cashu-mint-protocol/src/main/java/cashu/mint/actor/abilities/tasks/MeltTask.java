package cashu.mint.actor.abilities.tasks;

import cashu.common.model.Mint;
import cashu.common.model.PaymentMethod;
import cashu.common.model.rest.PostMeltRequest;
import cashu.common.model.rest.PostMeltResponse;
import cashu.common.protocol.BaseAbility;
import cashu.crypto.BDHKEUtils;
import cashu.mint.gateway.Gateway;
import lombok.Getter;
import lombok.extern.java.Log;

import java.util.logging.Level;

import static cashu.mint.util.MintUtil.createGateway;

@Log
public class MeltTask implements BaseAbility.Task<PostMeltResponse> {
    private final PostMeltRequest request;
    private final PaymentMethod method;
    private final Mint mint;

    @Getter
    private PostMeltResponse result;

    public MeltTask(PostMeltRequest request, PaymentMethod method, Mint mint) {
        this.request = request;
        this.method = method;
        this.mint = mint;
    }

    @Override
    public PostMeltResponse execute() {
        Gateway gateway = createGateway(method);
        var proofs = request.getProofs();
        var totalAmount = proofs.stream().mapToInt(proof -> proof.getAmount()).sum();
        proofs.forEach(proof -> {
            log.log(Level.INFO, "Verifying proof with parameters:({0}, {1}, {2})", new Object[]{proof.getSecret(), mint.getPrivateKey(), proof.getUnblindedSignature()});
            BDHKEUtils.verify(proof.getSecret().toString(), mint.getPrivateKey().toBytes(), proof.getUnblindedSignature().toBytes());
        });

        var amount = gateway.getAmount(request.getQuoteId());
        var fee_reserve = gateway.getFeeReserve(request.getQuoteId());

        if (totalAmount < amount + fee_reserve) {
            throw new RuntimeException("Proofs and blinded messages amounts do not match");
        }

        gateway.pay(request.getQuoteId());
        // TODO - revert to the gateway.checkPaymentStatus(request.getQuoteId()) call once the payment is implemented
        result = new PostMeltResponse(/*gateway.checkPaymentStatus(request.getQuoteId())*/ true, gateway.getPaymentPreimage(request.getQuoteId()));
        return result;
    }
}
