package xyz.tcheeric.cashu.mint.proto.tasks;

import xyz.tcheeric.cashu.common.model.Mint;
import xyz.tcheeric.cashu.common.model.PaymentMethod;
import xyz.tcheeric.cashu.common.model.PrivateKey;
import xyz.tcheeric.cashu.common.model.Proof;
import xyz.tcheeric.cashu.common.model.rest.PostMeltRequest;
import xyz.tcheeric.cashu.common.model.rest.PostMeltResponse;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.common.util.Task;
import xyz.tcheeric.cashu.crypto.BDHKEUtils;
import xyz.tcheeric.cashu.mint.proto.nut.NUT02;
import xyz.tcheeric.cashu.mint.proto.util.MintUtil;
import cashu.util.ThreadUtil;
import lombok.NonNull;
import lombok.extern.java.Log;

import static xyz.tcheeric.cashu.mint.proto.util.MintUtil.createGateway;

// TEST -
@Log
public class MeltTask implements Task<PostMeltResponse> {
    private final PostMeltRequest postMeltRequest;
    private final PaymentMethod method;
    private final Mint mint;

    public MeltTask(@NonNull PostMeltRequest postMeltRequest, @NonNull PaymentMethod method, @NonNull Mint mint) {
        this.postMeltRequest = postMeltRequest;
        this.method = method;
        this.mint = mint;
    }

    @Override
    public PostMeltResponse execute() throws CashuErrorException {
        ThreadUtil.MINT_MELT_LOCK.lock();
        try {
            // TODO - Use java module instead?
            var proofsToMelt = postMeltRequest.getInputs();
            for (Proof proof : proofsToMelt) {
                if (!verify(proof)) {
                    throw  new CashuErrorException("melt_proof_verification_error:"+proof);
                }
            }

            var keySetId = proofsToMelt.get(0).getKeySetId();
            var keyset = NUT02.keys(keySetId);
            var quoteId = postMeltRequest.getQuoteId();
            var gateway = createGateway(method);
            var amount = gateway.getAmount(quoteId);
            var request = gateway.getRequest(quoteId);
            var fee_reserve = gateway.getFeeReserve(quoteId); // TODO - Add 5% (configurable)
            var totalAmount = proofsToMelt.stream().mapToInt(proof -> proof.getAmount()).sum() + postMeltRequest.getFees(keyset) + fee_reserve;

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
