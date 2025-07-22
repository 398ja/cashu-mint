package xyz.tcheeric.cashu.mint.proto.nut;

import lombok.NonNull;
import lombok.extern.java.Log;
import xyz.tcheeric.cashu.common.KeySet;
import xyz.tcheeric.cashu.common.Keys;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.crypto.util.KeySetDerivation;
import xyz.tcheeric.cashu.entities.annotation.Nut;
import xyz.tcheeric.cashu.mint.proto.tasks.KeysetGeneratorTask;

import java.util.UUID;
import java.util.logging.Level;

@Nut(1)
@Log
public class NUT01 {

    public static KeySet generateKeySet(@NonNull UUID mintId, @NonNull String unit) throws CashuErrorException {
        log.log(Level.FINE, "generateKeySet({0}, {1})", new Object[]{mintId, unit});
        return new KeysetGeneratorTask(mintId.toString(), unit).execute();
    }

    public static KeySet generateKeySet(@NonNull String unit, @NonNull Keys keys) {
        KeySet keySet = KeySet.builder().unit(unit).keys(keys).build();
        keySet.setId(KeySetDerivation.getId(keys.values()));
        return keySet;
    }

}
