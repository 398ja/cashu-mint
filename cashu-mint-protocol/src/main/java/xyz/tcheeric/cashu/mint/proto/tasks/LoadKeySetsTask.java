package xyz.tcheeric.cashu.mint.proto.tasks;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.cashu.common.KeySet;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.mint.proto.nut.NUT01;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.impl.DefaultMintLoadService;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Slf4j
public class LoadKeySetsTask extends InstrumentedTask<List<KeySet>> {

    private final UUID mintId;
    private final MintLoadService mintLoadService;

    public LoadKeySetsTask(@NonNull UUID mintId) {
        this(mintId, new DefaultMintLoadService());
    }

    public LoadKeySetsTask(@NonNull UUID mintId, @NonNull MintLoadService mintLoadService) {
        this.mintId = mintId;
        this.mintLoadService = mintLoadService;
    }

    @Override
    protected List<KeySet> doExecute() throws CashuErrorException {
        log.debug("execute()");
        Mint mint = mintLoadService.load(mintId, false);
        Set<KeySet> keySets = mint.getKeySets();
        for (KeySet keySet : keySets) {
            if (keySet.getId() == null) {
                String unit = keySet.getUnit();
                try {
                    keySet.setId(NUT01.generateKeySet(mintId, unit).getId());
                } catch (CashuErrorException e) {
                    log.error("Failed to generate KeySet for mintId {} and unit {}: {}", mintId, unit, e.getMessage(), e);
                    throw e;
                }
            }
        }
        return new ArrayList<>(keySets);
    }
}
