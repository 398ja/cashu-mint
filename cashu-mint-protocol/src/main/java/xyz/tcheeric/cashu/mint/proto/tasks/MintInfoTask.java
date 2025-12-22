package xyz.tcheeric.cashu.mint.proto.tasks;

import lombok.NonNull;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.mint.proto.service.MintInfoService;
import xyz.tcheeric.cashu.mint.proto.util.MintInfo;

public class MintInfoTask extends InstrumentedTask<MintInfo> {

    private final MintInfoService mintInfoService;

    public MintInfoTask(@NonNull MintInfoService mintInfoService) {
        this.mintInfoService = mintInfoService;
    }

    @Override
    protected MintInfo doExecute() throws CashuErrorException {
        return mintInfoService.getMintInfo();
    }
}
