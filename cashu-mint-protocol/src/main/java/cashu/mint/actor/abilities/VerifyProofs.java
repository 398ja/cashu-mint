package cashu.mint.actor.abilities;

import cashu.common.annotation.Nut;
import cashu.common.protocol.BaseAbility;
import lombok.NonNull;

@Nut(3)
public class VerifyProofs extends BaseAbility<Boolean> {

    public VerifyProofs(@NonNull Task<Boolean> task) {
        super(task);
    }
}
