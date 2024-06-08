package cashu.mint.nut;

import cashu.common.annotation.Nut;
import cashu.common.model.KeySet;
import cashu.common.model.Keys;
import cashu.common.protocol.CashuErrorException;
import cashu.crypto.KeySetDerivation;
import cashu.mint.actor.abilities.KeysetGenerator;
import cashu.mint.actor.abilities.tasks.KeysetGeneratorTask;
import lombok.NonNull;
import lombok.extern.java.Log;

import java.util.logging.Level;

@Nut(1)
@Log
public class NUT01 {

    public static KeySet generateKeySet(@NonNull String unit) throws CashuErrorException {
        log.log(Level.INFO, "generateKeySet({0})", unit);
        return new KeysetGenerator(new KeysetGeneratorTask(unit)).apply();
    }

    public static KeySet generateKeySet(@NonNull String unit, @NonNull Keys keys) {
        KeySet keySet = KeySet.builder().unit(unit).keys(keys).build();
        KeySetDerivation keySetDerivation = new KeySetDerivation(keySet);
        keySetDerivation.deriveKeySetId();
        return keySet;
    }

}
