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
import xyz.tcheeric.cashu.entities.rest.ErrorResponse;
import xyz.tcheeric.cashu.entities.rest.PostMintRequest;
import xyz.tcheeric.cashu.entities.rest.PostMintResponse;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.SignatureVaultService;
import xyz.tcheeric.cashu.mint.proto.util.ThreadUtil;
import xyz.tcheeric.gateway.common.Gateway;

import java.util.List;

// TEST - When mint_invoice_not_paid_error is thrown, signBlindedMessage is never invoked, else it is invoked for each blindedMessage in the request
@Slf4j
public class MintTask<T extends Secret> implements Task<PostMintResponse> {
    private final PostMintRequest<T> postMintRequest;
    private final PaymentMethod method;
    private final String unit;
    private final Mint mint;
    private final MintProtocolService mintProtocolService;
    private final SignatureVaultService signatureVaultService;


    public MintTask(@NonNull PostMintRequest<T> postMintRequest,
                    @NonNull PaymentMethod method,
                    @NonNull Mint mint,
                    @NonNull MintProtocolService mintProtocolService,
                    @NonNull SignatureVaultService signatureVaultService) {
        this(postMintRequest, method, null, mint, mintProtocolService, signatureVaultService);
    }

    public MintTask(@NonNull PostMintRequest<T> postMintRequest,
                    @NonNull PaymentMethod method,
                    String unit,
                    @NonNull Mint mint,
                    @NonNull MintProtocolService mintProtocolService,
                    @NonNull SignatureVaultService signatureVaultService) {
        this.postMintRequest = postMintRequest;
        this.method = method;
        this.unit = unit;
        this.mint = mint;
        this.mintProtocolService = mintProtocolService;
        this.signatureVaultService = signatureVaultService;
    }

    @Override
    public PostMintResponse execute() throws CashuErrorException {
        ThreadUtil.MINT_MELT_LOCK.lock();
        try {
            PostMintResponse result = new PostMintResponse();

            // If the invoice was not paid yet, Bob responds with a structured error.
            if (log.isDebugEnabled()) {
                log.debug("Starting mint task: method={} unit={} blindedMessages={}", method, unit,
                        postMintRequest.getBlindedMessages() == null ? 0 : postMintRequest.getBlindedMessages().size());
            }
            Gateway gateway = unit == null ? mintProtocolService.createGateway(method)
                    : mintProtocolService.createGateway(method, unit);
            boolean paid = gateway.checkPaymentStatus(postMintRequest.getQuoteId());
            if (log.isDebugEnabled()) {
                log.debug("Payment status for quoteId={} paid={}", postMintRequest.getQuoteId(), paid);
            }
            if (!paid) {
                ErrorResponse error = new ErrorResponse("mint_invoice_not_paid_error");
                throw new CashuErrorException(error.toJson());
            }

            List<BlindedMessage> blindedMessages = postMintRequest.getBlindedMessages();
            if (log.isDebugEnabled()) {
                log.debug("Signing {} blinded messages...", blindedMessages == null ? 0 : blindedMessages.size());
            }
            for (BlindedMessage bm : blindedMessages) {
                SignBlindedMessageTask signBlindedMessageTask =
                        new SignBlindedMessageTask(mint, bm, mintProtocolService, signatureVaultService);
                BlindSignature bSignature = signBlindedMessageTask.execute();
                result.addBlindSignature(bSignature);
                if (log.isDebugEnabled()) {
                    log.debug("Signed blinded message amount={} keySetId={}", bm.getAmount(), bm.getKeySetId());
                }
            }

            return result;
        } finally {
            ThreadUtil.MINT_MELT_LOCK.unlock();
        }
    }
}
