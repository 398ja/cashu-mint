package cashu.mint.proto.abilities;

import cashu.common.annotation.Nut;
import cashu.common.protocol.BaseAbility;
import lombok.NonNull;

@Nut(3)
@Deprecated(forRemoval = true)
public class VerifyProofs extends BaseAbility<Void> {

    public VerifyProofs(@NonNull Task<Void> task) {
        super(task);
    }
}
