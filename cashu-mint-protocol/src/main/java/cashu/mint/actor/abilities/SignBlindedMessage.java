package cashu.mint.actor.abilities;

import cashu.common.annotation.Nut;
import cashu.common.model.BlindSignature;
import cashu.common.model.BlindedMessage;
import cashu.common.model.Signature;
import cashu.common.protocol.Ability;
import cashu.crypto.BDHKEUtils;
import cashu.mint.actor.Mint;
import cashu.util.ThreadUtil;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NonNull;

@Nut(3)
@AllArgsConstructor
public class SignBlindedMessage implements Ability<BlindSignature> {

    private final Mint mint;
    private final BlindedMessage blindedMessage;

    @Override
    public BlindSignature apply() {
        try {
            var task = new SignBlindedMessageTask(mint, blindedMessage);
            ThreadUtil.builder().blocking(true).task(task).build().run();
            return task.getResult();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }


    static class SignBlindedMessageTask implements ThreadUtil.Task<BlindSignature> {

        private final Mint mint;
        private final BlindedMessage blindedMessage;

        @Getter
        private BlindSignature result;

        public SignBlindedMessageTask(@NonNull Mint mint, @NonNull BlindedMessage blindedMessage) {
            this.mint = mint;
            this.blindedMessage = blindedMessage;
        }

        @Override
        public BlindSignature execute() {
            var signature = BDHKEUtils.signBlindedMessage(blindedMessage.getBlindedMessage().getBytes(), mint.getPrivateKey().getBytes());
            result = new BlindSignature(blindedMessage.getAmount(), blindedMessage.getKeySetId(), Signature.fromBytes(signature));
            return result;
        }
    }
}
