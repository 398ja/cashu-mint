package cashu.mint.nut;

import cashu.common.annotation.Nut;
import cashu.common.model.BlindSignature;
import cashu.common.model.BlindedMessage;
import cashu.common.model.Hex;
import cashu.common.model.PaymentMethod;
import cashu.common.model.Signature;
import cashu.common.model.rest.PostMintQuoteResponse;
import cashu.common.model.rest.PostMintRequest;
import cashu.common.model.rest.PostMintResponse;
import cashu.crypto.BDHKEUtils;
import cashu.mint.actor.Mint;
import cashu.mint.gateway.Gateway;
import cashu.mint.gateway.ln.LightningNetwork;
import lombok.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Nut(value = 4, description = "Mint tokens")
public class NUT04 {

    public static PostMintQuoteResponse quote(int amount, @NonNull PaymentMethod method) {
        Gateway gateway = createGateway(method);
        return PostMintQuoteResponse.builder().quoteId(UUID.randomUUID().toString()).request(gateway.createRequest(amount)).expiry(gateway.getPaymentExpiry()).build();
    }

    public static PostMintQuoteResponse quotePaymentStatus(@NonNull String quoteId, @NonNull PaymentMethod method) {
        Gateway gateway = createGateway(method);
        return PostMintQuoteResponse
                .builder()
                .quoteId(quoteId)
                .request(gateway.getRequest(quoteId))
                .expiry(gateway.getPaymentExpiry())
                .paid(gateway.checkPaymentStatus(quoteId))
                .build();
    }

    public static PostMintResponse mint(@NonNull PostMintRequest request, @NonNull PaymentMethod method, @NonNull Mint mint) {
        Gateway gateway = createGateway(method);
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

        return new PostMintResponse(signatures);
    }

    static Gateway createGateway(@NonNull PaymentMethod method) {
        return switch (method) {
            case BOLT11 -> new LightningNetwork();
            default -> throw new IllegalArgumentException("Unknown payment method: " + method);
        };
    }

}
