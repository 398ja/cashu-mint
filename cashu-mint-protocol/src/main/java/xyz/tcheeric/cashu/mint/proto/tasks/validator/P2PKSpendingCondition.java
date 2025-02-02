package xyz.tcheeric.cashu.mint.proto.tasks.validator;

import lombok.NoArgsConstructor;
import lombok.NonNull;
import xyz.tcheeric.cashu.common.model.P2PKSecret;
import xyz.tcheeric.cashu.common.model.Proof;

@NoArgsConstructor
public class P2PKSpendingCondition implements SpendingCondition<P2PKSecret> {

    @Override
    public void verify(@NonNull Proof<P2PKSecret> proof) {
    }
}
