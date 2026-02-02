package xyz.tcheeric.cashu.mint.proto.nut;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import xyz.tcheeric.cashu.entities.annotation.Nut;
import xyz.tcheeric.cashu.mint.proto.service.MintInfoService;
import xyz.tcheeric.cashu.mint.proto.tasks.MintInfoTask;
import xyz.tcheeric.cashu.mint.proto.util.MintInfo;
import xyz.tcheeric.cashu.common.util.CashuErrorException;

/**
 * NUT-06: Mint information.
 *
 * <p>This Spring-managed component provides mint metadata including name,
 * description, contact information, and supported NUT capabilities.
 *
 * <p><b>Security:</b> This endpoint is public and exposes only non-sensitive
 * mint metadata. No private keys or operational data are included.
 *
 * @see <a href="https://github.com/cashubtc/nuts/blob/main/06.md">NUT-06 Specification</a>
 */
@Nut(6)
@Component
public final class NUT06 {

    private final MintInfoService mintInfoService;

    @Autowired
    public NUT06(MintInfoService mintInfoService) {
        this.mintInfoService = mintInfoService;
    }

    public MintInfo mintInfo() throws CashuErrorException {
        return new MintInfoTask(mintInfoService).execute();
    }
}
