package xyz.tcheeric.cashu.mint.proto.nut;

import xyz.tcheeric.cashu.common.annotation.Nut;
import xyz.tcheeric.cashu.common.model.KeySet;
import xyz.tcheeric.cashu.common.model.Keys;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.crypto.util.KeySetDerivation;
import xyz.tcheeric.cashu.mint.proto.tasks.KeysetGeneratorTask;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;


@Nut(1)
@Slf4j
public class NUT01 {

    public static KeySet generateKeySet(@NonNull String unit) throws CashuErrorException {
        log.debug("generateKeySet({})", unit);
        return new KeysetGeneratorTask(unit).execute();
    }

    public static KeySet generateKeySet(@NonNull String unit, @NonNull Keys keys) {
        KeySet keySet = KeySet.builder().unit(unit).keys(keys).build();
        keySet.setId(KeySetDerivation.getId(keys.values()));
        return keySet;
    }

}
