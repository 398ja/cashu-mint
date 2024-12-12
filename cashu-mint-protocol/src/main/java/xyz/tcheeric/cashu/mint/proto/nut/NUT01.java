package xyz.tcheeric.cashu.mint.proto.nut;

import xyz.tcheeric.cashu.common.annotation.Nut;
import xyz.tcheeric.cashu.common.model.KeySet;
import xyz.tcheeric.cashu.common.model.Keys;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.crypto.KeySetDerivation;
import xyz.tcheeric.cashu.mint.proto.tasks.KeysetGeneratorTask;
import lombok.NonNull;
import lombok.extern.java.Log;

import java.util.logging.Level;

@Nut(1)
@Log
public class NUT01 {

    public static KeySet generateKeySet(@NonNull String unit) throws CashuErrorException {
        log.log(Level.FINE, "generateKeySet({0})", unit);
        return new KeysetGeneratorTask(unit).execute();
    }

    public static KeySet generateKeySet(@NonNull String unit, @NonNull Keys keys) {
        KeySet keySet = KeySet.builder().unit(unit).keys(keys).build();
        KeySetDerivation keySetDerivation = new KeySetDerivation(keySet);
        keySetDerivation.deriveKeySetId();
        return keySet;
    }

}
