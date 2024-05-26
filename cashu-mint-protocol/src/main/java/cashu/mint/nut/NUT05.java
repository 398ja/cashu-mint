package cashu.mint.nut;

import cashu.common.model.Mint;
import cashu.common.model.PaymentMethod;
import cashu.common.model.rest.PostMeltQuoteRequest;
import cashu.common.model.rest.PostMeltQuoteResponse;
import cashu.common.model.rest.PostMeltRequest;
import cashu.common.model.rest.PostMeltResponse;
import cashu.crypto.BDHKEUtils;
import cashu.mint.gateway.Gateway;
import cashu.util.ThreadUtil;
import cashu.vault.impl.fs.FSMintVault;
import lombok.Getter;
import lombok.NonNull;

import java.security.NoSuchAlgorithmException;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

import static cashu.mint.nut.NUT04.createGateway;

public class NUT05 {

    public static PostMeltQuoteResponse quote(@NonNull PostMeltQuoteRequest request, @NonNull PaymentMethod method) {
        var gateway = createGateway(method);
        var quoteId = UUID.randomUUID();
        var feeReserve = gateway.getFeeReserve(request.getRequestId());
        var expiry = gateway.getPaymentExpiry(quoteId.toString());
        var amount = gateway.getAmount(quoteId.toString());

        return PostMeltQuoteResponse
                .builder()
                .quoteId(quoteId.toString())
                .feeReserve(feeReserve)
                .expiry(expiry) // TODO - check if this is correct
                .amount(amount)
                .build();
    }

    public static PostMeltQuoteResponse quotePaymentStatus(@NonNull String quoteId, @NonNull PaymentMethod method) {
        Gateway gateway = createGateway(method);
        return PostMeltQuoteResponse
                .builder()
                .quoteId(quoteId)
                .expiry(gateway.getPaymentExpiry(quoteId))
                .paid(gateway.checkPaymentStatus(quoteId))
                .build();
    }

    public static PostMeltResponse melt(@NonNull PostMeltRequest request, @NonNull PaymentMethod method) {
        Mint mint = FSMintVault.load(false, true);

        var task = new MeltTask(request, method, mint);
        try {
            ThreadUtil.builder().blocking(true).task(task).lock(ThreadUtil.Locks.LOCK45).build().run();
        } catch (TimeoutException e) {
            throw new RuntimeException(e);
        }
        return task.getResult();
    }

    static class MeltTask implements ThreadUtil.Task<PostMeltResponse> {
        private final PostMeltRequest request;
        private final PaymentMethod method;
        private final Mint mint;

        @Getter
        private PostMeltResponse result;

        public MeltTask(PostMeltRequest request, PaymentMethod method, Mint mint) {
            this.request = request;
            this.method = method;
            this.mint = mint;
        }

        @Override
        public PostMeltResponse execute() {
            Gateway gateway = createGateway(method);
            var proofs = request.getProofs();
            var totalAmount = proofs.stream().mapToInt(proof -> proof.getAmount()).sum();
            proofs.forEach(proof -> {
                try {
                    BDHKEUtils.verify(proof.getSecret().toString(), mint.getPrivateKey().toBytes(), proof.getUnblindedSignature().toBytes());
                } catch (NoSuchAlgorithmException e) {
                    throw new RuntimeException(e);
                }
            });

            var amount = gateway.getAmount(request.getQuoteId());
            var fee_reserve = gateway.getFeeReserve(request.getQuoteId());

            if(totalAmount < amount + fee_reserve) {
                throw new RuntimeException("Proofs and blinded messages amounts do not match");
            }

            gateway.pay(request.getQuoteId());
            result = new PostMeltResponse(gateway.checkPaymentStatus(request.getQuoteId()), gateway.getPaymentPreimage(request.getQuoteId()));
            return result;
        }
    }

}
