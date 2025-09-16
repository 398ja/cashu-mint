package xyz.tcheeric.cashu.mint.admin.cli.port.stub;

import xyz.tcheeric.cashu.mint.admin.cli.model.MintStatusRequest;
import xyz.tcheeric.cashu.mint.admin.cli.model.MintStatusResponse;
import xyz.tcheeric.cashu.mint.admin.cli.port.MintStatusPort;

/**
 * Stub implementation providing deterministic status responses.
 */
public final class StubMintStatusPort implements MintStatusPort {

    @Override
    public MintStatusResponse fetchStatus(final MintStatusRequest request) {
        return new MintStatusResponse(
            request.mintId(),
            "ACTIVE",
            4,
            1
        );
    }
}
