package xyz.tcheeric.cashu.mint.proto.nut;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.cashu.common.ActiveKeySet;
import xyz.tcheeric.cashu.common.KeySet;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.annotation.Nut;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.mint.proto.tasks.ActiveKeySetsTask;
import xyz.tcheeric.cashu.mint.proto.tasks.LoadKeySetTask;
import xyz.tcheeric.cashu.mint.proto.tasks.LoadKeySetsTask;

import java.util.List;
import java.util.UUID;

/**
 * NUT-02: Keysets and keyset IDs.
 *
 * <p>This class provides static methods for keyset management including
 * retrieval of active keysets and individual keyset lookups.
 *
 * <p><b>Security:</b> Keyset IDs are derived from public keys using SHA-256.
 * This ensures keysets cannot be forged without knowledge of the private keys.
 *
 * @see <a href="https://github.com/cashubtc/nuts/blob/main/02.md">NUT-02 Specification</a>
 */
@Slf4j
@Nut(2)
public final class NUT02 {

    private NUT02() {
        // Utility class - prevent instantiation
    }

    public static List<KeySet> keys(UUID mintId) throws CashuErrorException {
        return new LoadKeySetsTask(mintId).execute();
    }

    public static List<KeySet> keys(UUID mintId, @NonNull MintLoadService mintLoadService) throws CashuErrorException {
        return new LoadKeySetsTask(mintId, mintLoadService).execute();
    }

    public static KeySet keys(@NonNull String keysetId, MintLoadService mintLoadService) throws CashuErrorException {
        return new LoadKeySetTask(keysetId, mintLoadService).execute();
    }

    public static List<ActiveKeySet> activeKeySets(MintLoadService mintLoadService) throws CashuErrorException {
        return new ActiveKeySetsTask(mintLoadService).execute();
    }
}
