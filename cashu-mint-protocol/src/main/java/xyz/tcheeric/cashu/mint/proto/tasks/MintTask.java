package xyz.tcheeric.cashu.mint.proto.tasks;

import cashu.util.ThreadUtil;
import lombok.NonNull;
import lombok.extern.java.Log;
import xyz.tcheeric.cashu.common.model.BlindSignature;
import xyz.tcheeric.cashu.common.model.BlindedMessage;
import xyz.tcheeric.cashu.common.model.Mint;
import xyz.tcheeric.cashu.common.model.PaymentMethod;
import xyz.tcheeric.cashu.common.model.Secret;
import xyz.tcheeric.cashu.common.model.rest.PostMintRequest;
import xyz.tcheeric.cashu.common.model.rest.PostMintResponse;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.common.util.Task;
import xyz.tcheeric.cashu.gateway.Gateway;

import java.util.List;

import static xyz.tcheeric.cashu.mint.proto.util.MintProtocolUtil.createGateway;

// TEST - When mint_invoice_not_paid_error is thrown, signBlindedMessage is never invoked, else it is invoked for each blindedMessage in the request
@Log
public class MintTask<T extends Secret> implements Task<PostMintResponse> {
    private final PostMintRequest<T> postMintRequest;
    private final PaymentMethod method;
    private final Mint mint;


    public MintTask(@NonNull PostMintRequest<T> postMintRequest, @NonNull PaymentMethod method, @NonNull Mint mint) {
        this.postMintRequest = postMintRequest;
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
            Gateway gateway = createGateway(method);
            if (!gateway.checkPaymentStatus(postMintRequest.getQuoteId())) {
                throw new CashuErrorException("mint_invoice_not_paid_error");
            }

            List<BlindedMessage> blindedMessages = postMintRequest.getBlindedMessages();
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
