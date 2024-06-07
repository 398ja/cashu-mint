package cashu.mint.actor.abilities;

import cashu.common.annotation.Nut;
import cashu.common.protocol.BaseAbility;
import lombok.NonNull;
import lombok.extern.java.Log;

@Nut(3)
@Log
public class InvalidateProofs extends BaseAbility<Boolean> {

    public InvalidateProofs(@NonNull Task<Boolean> task) {
        super(task);
    }
}
