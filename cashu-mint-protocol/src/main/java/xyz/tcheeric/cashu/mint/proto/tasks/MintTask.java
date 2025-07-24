package xyz.tcheeric.cashu.mint.proto.tasks;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.cashu.common.BlindSignature;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.PaymentMethod;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.common.util.Task;
import xyz.tcheeric.cashu.entities.rest.PostMintRequest;
import xyz.tcheeric.cashu.entities.rest.PostMintResponse;
import xyz.tcheeric.cashu.gateway.Gateway;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.util.ThreadUtil;

import java.util.List;

// TEST - When mint_invoice_not_paid_error is thrown, signBlindedMessage is never invoked, else it is invoked for each blindedMessage in the request
@Slf4j
public class MintTask<T extends Secret> implements Task<PostMintResponse> {
    private final PostMintRequest<T> postMintRequest;
    private final PaymentMethod method;
    private final Mint mint;
    private final MintProtocolService mintProtocolService;


    public MintTask(@NonNull PostMintRequest<T> postMintRequest, @NonNull PaymentMethod method, @NonNull Mint mint,
                    @NonNull MintProtocolService mintProtocolService) {
        this.postMintRequest = postMintRequest;
        this.method = method;
        this.mint = mint;
        this.mintProtocolService = mintProtocolService;
    }

    @Override
    public PostMintResponse execute() throws CashuErrorException {
        ThreadUtil.MINT_MELT_LOCK.lock();
        try {
            PostMintResponse result = new PostMintResponse();

            // If the invoice was not paid yet, Bob responds with an error.
            // TODO - Encode the error message
            Gateway gateway = mintProtocolService.createGateway(method);
            if (!gateway.checkPaymentStatus(postMintRequest.getQuoteId())) {
                throw new CashuErrorException("mint_invoice_not_paid_error");
            }

            List<BlindedMessage> blindedMessages = postMintRequest.getBlindedMessages();
            blindedMessages.forEach(bm -> {
                SignBlindedMessageTask signBlindedMessageTask = new SignBlindedMessageTask(mint, bm, mintProtocolService);
                BlindSignature bSignature = signBlindedMessageTask.execute();
                result.addBlindSignature(bSignature);
            });

            return result;
        } finally {
            ThreadUtil.MINT_MELT_LOCK.unlock();
        }
    }
}
