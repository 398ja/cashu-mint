package xyz.tcheeric.cashu.mint.admin.cli.port;

import xyz.tcheeric.cashu.mint.admin.cli.model.MintConfigRequest;
import xyz.tcheeric.cashu.mint.admin.cli.model.MintConfigResponse;

public interface MintConfigPort {

    MintConfigResponse applyConfiguration(MintConfigRequest request);
}
