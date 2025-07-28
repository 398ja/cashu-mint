package xyz.tcheeric.cashu.mint.proto.tasks;

import lombok.NonNull;
import xyz.tcheeric.cashu.common.util.Task;
import xyz.tcheeric.cashu.mint.proto.service.MintInfoService;
import xyz.tcheeric.cashu.mint.proto.util.MintInfo;

public class MintInfoTask implements Task<MintInfo> {

    private final MintInfoService mintInfoService;

    public MintInfoTask(@NonNull MintInfoService mintInfoService) {
        this.mintInfoService = mintInfoService;
    }

    @Override
    public MintInfo execute() {
        return mintInfoService.getMintInfo();
    }
}
