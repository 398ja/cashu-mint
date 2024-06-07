package cashu.mint.actor.abilities.tasks;

import cashu.common.model.BlindSignature;
import cashu.common.model.BlindedMessage;
import cashu.common.model.Mint;
import cashu.common.model.PaymentMethod;
import cashu.common.model.Signature;
import cashu.common.model.rest.PostMintRequest;
import cashu.common.model.rest.PostMintResponse;
import cashu.common.protocol.BaseAbility;
import cashu.crypto.BDHKEUtils;
import cashu.mint.gateway.Gateway;
import lombok.Getter;

import java.util.List;

import static cashu.mint.util.MintUtil.createGateway;

public class MintTask implements BaseAbility.Task<PostMintResponse> {
    private final PostMintRequest request;
    private final PaymentMethod method;
    private final Mint mint;

    @Getter
    private final PostMintResponse result;

    public MintTask(PostMintRequest postMintRequest, PaymentMethod method, Mint mint) {
        this.request = postMintRequest;
        this.method = method;
        this.mint = mint;
        this.result = new PostMintResponse();
    }

    @Override
    public PostMintResponse execute() {

        // If the invoice was not paid yet, Bob responds with an error.
        // TODO - Encode the error message
        Gateway gateway = createGateway(method);
        if (!gateway.checkPaymentStatus(request.getQuoteId())) {
            throw new IllegalStateException("Payment not received yet");
        }

        List<BlindedMessage> blindedMessages = request.getBlindedMessages();

        blindedMessages.forEach(blindedMessage -> {
            BlindSignature signature = new BlindSignature();
            signature.setAmount(blindedMessage.getAmount());
            signature.setKeySetId(blindedMessage.getKeySetId());
            var B_ = blindedMessage.getBlindedMessage().getBytes();
            var k = mint.getPrivateKey().getBytes();
            signature.setBlindedSignature(Signature.fromBytes(BDHKEUtils.signBlindedMessage(B_, k)));
            result.addBlindSignature(signature);
        });

        return result;
    }
}
