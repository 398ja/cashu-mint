package xyz.tcheeric.cashu.mint.proto.nut;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.cashu.common.KeySet;
import xyz.tcheeric.cashu.common.Keys;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.crypto.util.KeySetDerivation;
import xyz.tcheeric.cashu.entities.annotation.Nut;
import xyz.tcheeric.cashu.mint.proto.tasks.KeysetGeneratorTask;

import java.util.UUID;

@Nut(1)
@Slf4j
public class NUT01 {

    public static KeySet generateKeySet(@NonNull UUID mintId, @NonNull String unit) throws CashuErrorException {
        log.debug("generateKeySet({}, {})", mintId, unit);
        return new KeysetGeneratorTask(mintId.toString(), unit).execute();
    }

    public static KeySet generateKeySet(@NonNull String unit, @NonNull Keys keys) {
        KeySet keySet = KeySet.builder().unit(unit).keys(keys).build();
        keySet.setId(KeySetDerivation.getId(keys.values()));
        return keySet;
    }

}
