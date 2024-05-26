package cashu.mint.nut;

import cashu.common.annotation.Nut;
import cashu.common.model.BlindSignature;
import cashu.common.model.BlindedMessage;
import cashu.common.model.Mint;
import cashu.common.model.PaymentMethod;
import cashu.common.model.Signature;
import cashu.common.model.rest.PostMintQuoteResponse;
import cashu.common.model.rest.PostMintRequest;
import cashu.common.model.rest.PostMintResponse;
import cashu.crypto.BDHKEUtils;
import cashu.mint.gateway.Gateway;
import cashu.mint.gateway.mock.MockGateway;
import cashu.util.ThreadUtil;
import cashu.vault.impl.fs.FSMintVault;
import lombok.Getter;
import lombok.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Nut(value = 4, description = "Mint tokens")
public class NUT04 {

    public static PostMintQuoteResponse quote(int amount, @NonNull PaymentMethod method) {

        var gateway = createGateway(method);
        var quoteId = UUID.randomUUID();
        var request = gateway.createRequest(amount);
        var expiry = gateway.getPaymentExpiry(quoteId.toString());

        return PostMintQuoteResponse.builder()
                .quoteId(quoteId.toString())
                .request(request)
                .expiry(expiry) // TODO - check if this is correct
                .build();
    }

    public static PostMintQuoteResponse quotePaymentStatus(@NonNull String quoteId, @NonNull PaymentMethod method) {
        Gateway gateway = createGateway(method);
        return PostMintQuoteResponse
                .builder()
                .quoteId(quoteId)
                .request(gateway.getRequest(quoteId))
                .expiry(gateway.getPaymentExpiry(quoteId))
                .paid(gateway.checkPaymentStatus(quoteId))
                .build();
    }

    public static PostMintResponse mint(@NonNull PostMintRequest request, @NonNull PaymentMethod method) {
        Mint mint = FSMintVault.load(false, true);
        var task = new MintTask(request, method, mint);
        try {
            ThreadUtil.builder().blocking(true).task(task).lock(ThreadUtil.Locks.LOCK45).build().run();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return task.getResult();
    }

    static class MintTask implements ThreadUtil.Task<PostMintResponse> {
        private final PostMintRequest request;
        private final PaymentMethod method;
        private final Mint mint;

        @Getter
        private PostMintResponse result;

        public MintTask(PostMintRequest request, PaymentMethod method, Mint mint) {
            this.request = request;
            this.method = method;
            this.mint = mint;
        }

        @Override
        public PostMintResponse execute() {
            Gateway gateway = createGateway(method);

            // If the invoice was not paid yet, Bob responds with an error.
            if (!gateway.checkPaymentStatus(request.getQuoteId())) {
                throw new RuntimeException("Payment not received yet");
            }

            List<BlindedMessage> blindedMessages = request.getBlindedMessages();
            List<BlindSignature> signatures = new ArrayList<>();
            blindedMessages.forEach(blindedMessage -> {
                BlindSignature signature = new BlindSignature();
                signature.setAmount(blindedMessage.getAmount());
                signature.setKeySetId(blindedMessage.getKeySetId());
                var B_ = blindedMessage.getBlindedMessage().getBytes();
                var k = mint.getPrivateKey().getBytes();
                signature.setBlindedSignature(Signature.fromBytes(BDHKEUtils.signBlindedMessage(B_, k)));
                signatures.add(signature);
            });

            result = new PostMintResponse(signatures);
            return result;
        }
    }

    static Gateway createGateway(@NonNull PaymentMethod method) {
        return switch (method) {
            case BOLT11 -> new MockGateway();
            default -> throw new IllegalArgumentException("Unknown payment method: " + method);
        };
    }

}
