package cashu.mint.actor.abilities;

import cashu.common.annotation.Nut;
import cashu.common.model.BlindSignature;
import cashu.common.model.BlindedMessage;
import cashu.common.model.Hex;
import cashu.common.model.Signature;
import cashu.common.protocol.Ability;
import cashu.crypto.BDHKEUtils;
import cashu.mint.actor.Mint;
import lombok.AllArgsConstructor;

@Nut(3)
@AllArgsConstructor
public class SignBlindedMessage implements Ability<BlindSignature> {

    private final Mint mint;
    private final BlindedMessage blindedMessage;

    @Override
    public BlindSignature apply() {
        var signature = BDHKEUtils.signBlindedMessage(blindedMessage.getBlindedMessage().getBytes(), mint.getPrivateKey().getBytes());
        return new BlindSignature(blindedMessage.getAmount(), blindedMessage.getKeySetId(), Signature.fromBytes(signature));
    }
}
