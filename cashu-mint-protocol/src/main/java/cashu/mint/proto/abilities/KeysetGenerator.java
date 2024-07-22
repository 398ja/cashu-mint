package cashu.mint.proto.abilities;

import cashu.common.annotation.Nut;
import cashu.common.model.KeySet;
import cashu.common.protocol.BaseAbility;
import lombok.NonNull;
import lombok.extern.java.Log;

@Log
@Nut(1)
@Deprecated(forRemoval = true)
public class KeysetGenerator extends BaseAbility<KeySet> {

    public KeysetGenerator(@NonNull Task<KeySet> task) {
        super(task);
    }

}
