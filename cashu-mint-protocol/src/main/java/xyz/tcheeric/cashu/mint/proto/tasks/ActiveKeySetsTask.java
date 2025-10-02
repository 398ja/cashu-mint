package xyz.tcheeric.cashu.mint.proto.tasks;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.cashu.common.ActiveKeySet;
import xyz.tcheeric.cashu.common.KeySet;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.common.util.Task;
import xyz.tcheeric.cashu.mint.proto.service.DefaultMintLoadService;
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
        List<ActiveKeySet> result = new ArrayList<>();

        mintLoadService.keySets(false)
                .stream()
                .map(keySet -> ActiveKeySet.fromKeySet(keySet, true))
                .forEach(result::add);

        mintLoadService.keySets(true)
                .stream()
                .map(keySet -> ActiveKeySet.fromKeySet(keySet, false))
                .forEach(result::add);

        result.sort(Comparator.comparing(ActiveKeySet::getId));

        return result;
    }
}
