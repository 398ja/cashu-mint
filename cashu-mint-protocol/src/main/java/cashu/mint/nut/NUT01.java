package cashu.mint.nut;

import cashu.common.annotation.Nut;
import cashu.common.error.CashuException;
import cashu.common.model.KeySet;
import cashu.mint.actor.abilities.KeysetGenerator;
import lombok.NonNull;

@Nut(1)
public class NUT01 {

    public static KeySet generateKeySet(@NonNull String unit) {
        return new KeysetGenerator(unit).apply();
    }
}
