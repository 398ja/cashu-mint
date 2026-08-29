package xyz.tcheeric.cashu.mint.proto.nut;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.cashu.common.KeySet;
import xyz.tcheeric.cashu.common.Keys;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.crypto.util.KeySetIdV2Derivation;
import xyz.tcheeric.cashu.entities.annotation.Nut;
import xyz.tcheeric.cashu.mint.proto.tasks.KeysetGeneratorTask;

import java.util.UUID;

/**
 * NUT-01: Mint public key exchange.
 *
 * <p>This class provides static methods for retrieving mint public keys.
 * All methods require a valid mint instance from the vault.
 *
 * <p><b>Security:</b> Public keys returned by this class are used for
 * client-side blinding operations. The corresponding private keys are
 * never exposed through this API.
 *
 * @see <a href="https://github.com/cashubtc/nuts/blob/main/01.md">NUT-01 Specification</a>
 */
@Nut(1)
@Slf4j
public final class NUT01 {

    private NUT01() {
        // Utility class - prevent instantiation
    }

    public static KeySet generateKeySet(@NonNull UUID mintId, @NonNull String unit) throws CashuErrorException {
        log.debug("generateKeySet({}, {})", mintId, unit);
        return new KeysetGeneratorTask(mintId.toString(), unit).execute();
    }

    public static KeySet generateKeySet(@NonNull String unit, @NonNull Keys keys) {
        KeySet keySet = KeySet.builder().unit(unit).keys(keys).build();
        keySet.setId(KeySetIdV2Derivation.getId(
                keys.values(), unit, keySet.getPartPerThousand(), null));
        return keySet;
    }

}
