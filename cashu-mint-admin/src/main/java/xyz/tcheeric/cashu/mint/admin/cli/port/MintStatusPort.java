package xyz.tcheeric.cashu.mint.admin.cli.port;

import xyz.tcheeric.cashu.mint.admin.cli.model.MintStatusRequest;
import xyz.tcheeric.cashu.mint.admin.cli.model.MintStatusResponse;

public interface MintStatusPort {

    MintStatusResponse fetchStatus(MintStatusRequest request);
}
