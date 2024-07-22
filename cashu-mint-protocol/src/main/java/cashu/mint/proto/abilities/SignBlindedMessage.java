package cashu.mint.proto.abilities;

import cashu.common.annotation.Nut;
import cashu.common.model.BlindSignature;
import cashu.common.protocol.BaseAbility;
import lombok.NonNull;

@Nut(3)
@Deprecated(forRemoval = true)
public class SignBlindedMessage extends BaseAbility<BlindSignature> {

    public SignBlindedMessage(@NonNull Task<BlindSignature> task) {
        super(task);
    }

}
