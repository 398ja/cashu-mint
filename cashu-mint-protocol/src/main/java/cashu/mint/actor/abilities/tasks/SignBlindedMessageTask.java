package cashu.mint.actor.abilities.tasks;

import cashu.common.model.BlindSignature;
import cashu.common.model.BlindedMessage;
import cashu.common.model.Mint;
import cashu.common.model.Signature;
import cashu.common.protocol.BaseAbility;
import cashu.crypto.BDHKEUtils;
import lombok.Getter;
import lombok.NonNull;

public class SignBlindedMessageTask implements BaseAbility.Task<BlindSignature> {

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
