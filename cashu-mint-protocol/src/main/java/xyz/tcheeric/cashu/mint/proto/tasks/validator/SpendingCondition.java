package xyz.tcheeric.cashu.mint.proto.tasks.validator;

import lombok.NonNull;
import xyz.tcheeric.cashu.common.Proof;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;

public interface SpendingCondition<T extends Secret> {

    void verify(@NonNull Proof<T> proof) throws CashuErrorException;
}
