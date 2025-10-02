package xyz.tcheeric.cashu.mint.proto.nut;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.cashu.common.ActiveKeySet;
import xyz.tcheeric.cashu.common.KeySet;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.annotation.Nut;
import xyz.tcheeric.cashu.mint.proto.service.DefaultMintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.mint.proto.tasks.ActiveKeySetsTask;
import xyz.tcheeric.cashu.mint.proto.tasks.LoadKeySetTask;
import xyz.tcheeric.cashu.mint.proto.tasks.LoadKeySetsTask;

import java.util.List;
import java.util.UUID;

@Slf4j
@Nut(2)
public class NUT02 {

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
