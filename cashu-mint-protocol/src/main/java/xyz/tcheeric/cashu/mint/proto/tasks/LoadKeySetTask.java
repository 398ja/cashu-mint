package xyz.tcheeric.cashu.mint.proto.tasks;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.cashu.common.KeySet;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.common.util.Task;
import xyz.tcheeric.cashu.mint.proto.service.impl.DefaultMintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;

import java.util.List;

@Slf4j
public class LoadKeySetTask implements Task<KeySet> {

    private final String keysetId;
    private final MintLoadService mintLoadService;

    public LoadKeySetTask(@NonNull String keysetId) {
        this(keysetId, new DefaultMintLoadService());
    }

    public LoadKeySetTask(@NonNull String keysetId, @NonNull MintLoadService mintLoadService) {
        this.keysetId = keysetId;
        this.mintLoadService = mintLoadService;
    }

    @Override
    public KeySet execute() throws CashuErrorException {
        List<KeySet> keySets = mintLoadService.keySets();
        log.debug("keysets: {}", keySets);
        return keySets
                .stream()
                .filter(keySet -> null != keySet.getId())
                .filter(keySet -> keySet.getId().equals(keysetId))
                .findFirst()
                .orElseThrow();
    }
}
