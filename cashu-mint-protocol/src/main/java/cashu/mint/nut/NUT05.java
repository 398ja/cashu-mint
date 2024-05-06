package cashu.mint.nut;

import cashu.common.model.PaymentMethod;
import cashu.common.model.rest.PostMeltQuoteRequest;
import cashu.common.model.rest.PostMeltQuoteResponse;
import cashu.common.model.rest.PostMeltRequest;
import cashu.common.model.rest.PostMeltResponse;
import cashu.crypto.BDHKEUtils;
import cashu.mint.actor.Mint;
import cashu.mint.gateway.Gateway;
import lombok.NonNull;

import java.security.NoSuchAlgorithmException;
import java.util.UUID;

import static cashu.mint.nut.NUT04.createGateway;

public class NUT05 {

    public static PostMeltQuoteResponse quote(@NonNull PostMeltQuoteRequest request, @NonNull PaymentMethod method) {
        Gateway gateway = createGateway(method);
        return PostMeltQuoteResponse
                .builder()
                .quoteId(UUID.randomUUID().toString())
                .feeReserve(gateway.getFeeReserve(request.getRequestId()))
                .expiry(gateway.getPaymentExpiry())
                .build();
    }

    public static PostMeltQuoteResponse quotePaymentStatus(@NonNull String quoteId, @NonNull PaymentMethod method) {
        Gateway gateway = createGateway(method);
        return PostMeltQuoteResponse
                .builder()
                .quoteId(quoteId)
                .expiry(gateway.getPaymentExpiry())
                .paid(gateway.checkPaymentStatus(quoteId))
                .build();
    }

    // TODO - handle thread-safety and concurrency
    public static PostMeltResponse melt(@NonNull PostMeltRequest request, @NonNull PaymentMethod method, @NonNull Mint mint) {
        Gateway gateway = createGateway(method);
        var proofs = request.getProofs();
        proofs.forEach(proof -> {
            try {
                BDHKEUtils.verify(proof.getSecret().toString(), mint.getPrivateKey().toBytes(), proof.getUnblindedSignature().toBytes());
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException(e);
            }
        });
        gateway.pay(request.getQuoteId());
        return new PostMeltResponse(gateway.checkPaymentStatus(request.getQuoteId()), gateway.getPaymentPreimage(request.getQuoteId()));
    }

}
