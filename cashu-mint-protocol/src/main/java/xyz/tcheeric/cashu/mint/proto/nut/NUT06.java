package xyz.tcheeric.cashu.mint.proto.nut;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import xyz.tcheeric.cashu.entities.annotation.Nut;
import xyz.tcheeric.cashu.mint.proto.service.MintInfoService;
import xyz.tcheeric.cashu.mint.proto.tasks.MintInfoTask;
import xyz.tcheeric.cashu.mint.proto.util.MintInfo;

@Nut(6)
@Component
public class NUT06 {

    private final MintInfoService mintInfoService;

    @Autowired
    public NUT06(MintInfoService mintInfoService) {
        this.mintInfoService = mintInfoService;
    }

    public MintInfo mintInfo() {
        return new MintInfoTask(mintInfoService).execute();
    }
}
