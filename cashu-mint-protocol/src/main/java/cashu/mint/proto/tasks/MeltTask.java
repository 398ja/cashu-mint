package cashu.mint.proto.tasks;

import cashu.common.model.Mint;
import cashu.common.model.PaymentMethod;
import cashu.common.model.PrivateKey;
import cashu.common.model.Proof;
import cashu.common.model.rest.PostMeltRequest;
import cashu.common.model.rest.PostMeltResponse;
import cashu.common.util.CashuErrorException;
import cashu.common.util.Task;
import cashu.crypto.BDHKEUtils;
import cashu.gateway.Gateway;
import cashu.mint.proto.util.MintUtil;
import cashu.util.ThreadUtil;
import lombok.NonNull;
import lombok.extern.java.Log;

import static cashu.mint.proto.util.MintUtil.createGateway;

// TEST -
@Log
public class MeltTask implements Task<PostMeltResponse> {
    private final PostMeltRequest request;
    private final PaymentMethod method;
    private final Mint mint;

    public MeltTask(@NonNull PostMeltRequest request, @NonNull PaymentMethod method, @NonNull Mint mint) {
        this.request = request;
        this.method = method;
        this.mint = mint;
    }

    @Override
    public PostMeltResponse execute() throws CashuErrorException {
        ThreadUtil.MINT_MELT_LOCK.lock();
        try {
            // TODO - Use java module instead?
            Gateway gateway = createGateway(method, "melt");
            var proofsToMelt = request.getProofs();
            var totalAmount = proofsToMelt.stream().mapToInt(proof -> proof.getAmount()).sum();

            for (Proof proof : proofsToMelt) {
                if (!verify(proof)) {
                    throw  new CashuErrorException("melt_proof_verification_error:"+proof);
                }
            }

            String quoteId = request.getQuoteId();
            var amount = gateway.getAmount(quoteId);
            var fee_reserve = gateway.getFeeReserve(quoteId);

            if (totalAmount < amount + fee_reserve) {
                throw new CashuErrorException("melt_proof_amount_error");
            }

            gateway.pay(quoteId);

            // Invalidate the proofsToMelt.
            new InvalidateProofsTask(mint, proofsToMelt).execute();

            // TODO - revert to the gateway.checkPaymentStatus(request.getQuoteId()) call once the payment is implemented
            return new PostMeltResponse(/*gateway.checkPaymentStatus(request.getQuoteId())*/ true, gateway.getPaymentPreimage(quoteId));
        } finally {
            ThreadUtil.MINT_MELT_LOCK.unlock();
        }
    }
    public boolean verify(@NonNull Proof proof) {
        PrivateKey privateKey = MintUtil.getPrivateKey(proof.getKeySetId(), proof.getAmount(), mint);
        if (privateKey != null) {
            return BDHKEUtils.verify(proof.getSecret().toString(), privateKey.toBytes(), proof.getUnblindedSignature().toBytes());
        }
        return false;
    }
}
