package cashu.mint.proto.tasks;

import cashu.common.model.BlindSignature;
import cashu.common.model.BlindedMessage;
import cashu.common.model.Mint;
import cashu.common.model.PaymentMethod;
import cashu.common.model.rest.PostMintRequest;
import cashu.common.model.rest.PostMintResponse;
import cashu.common.protocol.BaseAbility;
import cashu.common.protocol.CashuErrorException;
import cashu.gateway.Gateway;
import cashu.util.ThreadUtil;
import lombok.NonNull;
import lombok.extern.java.Log;

import java.util.List;

import static cashu.mint.proto.util.MintUtil.createGateway;

// TEST - When mint_invoice_not_paid_error is thrown, signBlindedMessage is never invoked, else it is invoked for each blindedMessage in the request
@Log
public class MintTask implements BaseAbility.Task<PostMintResponse> {
    private final PostMintRequest request;
    private final PaymentMethod method;
    private final Mint mint;


    public MintTask(@NonNull PostMintRequest postMintRequest, @NonNull PaymentMethod method, @NonNull Mint mint) {
        this.request = postMintRequest;
        this.method = method;
        this.mint = mint;
    }

    @Override
    public PostMintResponse execute() throws CashuErrorException {
        ThreadUtil.MINT_MELT_LOCK.lock();
        try {
            PostMintResponse result = new PostMintResponse();

            // If the invoice was not paid yet, Bob responds with an error.
            // TODO - Encode the error message
            Gateway gateway = createGateway(method, "mint");
            if (!gateway.checkPaymentStatus(request.getQuoteId())) {
                throw new CashuErrorException("mint_invoice_not_paid_error");
            }

            List<BlindedMessage> blindedMessages = request.getBlindedMessages();
            blindedMessages.forEach(bm -> {
                SignBlindedMessageTask signBlindedMessageTask = new SignBlindedMessageTask(mint, bm);
                BlindSignature bSignature = signBlindedMessageTask.execute();
                result.addBlindSignature(bSignature);
            });

            return result;
        } finally {
            ThreadUtil.MINT_MELT_LOCK.unlock();
        }
    }
}
