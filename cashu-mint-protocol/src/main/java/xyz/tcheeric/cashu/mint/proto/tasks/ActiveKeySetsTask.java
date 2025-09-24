package xyz.tcheeric.cashu.mint.proto.tasks;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.cashu.common.ActiveKeySet;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.common.util.Task;
import xyz.tcheeric.cashu.mint.proto.service.impl.DefaultMintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Slf4j
public class ActiveKeySetsTask implements Task<List<ActiveKeySet>> {

    private final MintLoadService mintLoadService;

    public ActiveKeySetsTask() {
        this(new DefaultMintLoadService());
    }

    public ActiveKeySetsTask(@NonNull MintLoadService mintLoadService) {
        this.mintLoadService = mintLoadService;
    }

    @Override
    public List<ActiveKeySet> execute() throws CashuErrorException {
        log.debug("execute()");
        // Deduplicate by keyset id. If a keyset appears in both sources, prefer active=true.
        java.util.Map<String, ActiveKeySet> byId = new java.util.HashMap<>();

        // First, add archived/inactive keysets as inactive
        for (var keySet : mintLoadService.keySets(true)) {
            byId.put(keySet.getId(), ActiveKeySet.fromKeySet(keySet, false));
        }

        // Then, add active keysets and override the flag to true if same id was seen
        for (var keySet : mintLoadService.keySets(false)) {
            byId.put(keySet.getId(), ActiveKeySet.fromKeySet(keySet, true));
        }

        List<ActiveKeySet> result = new ArrayList<>(byId.values());
        result.sort(Comparator.comparing(ActiveKeySet::getId));
        return result;
    }
}
