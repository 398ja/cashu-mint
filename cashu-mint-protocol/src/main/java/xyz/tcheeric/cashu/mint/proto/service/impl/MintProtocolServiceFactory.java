package xyz.tcheeric.cashu.mint.proto.service.impl;

import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;

public final class MintProtocolServiceFactory {
    private static MintProtocolService instance = new DefaultMintProtocolService();

    private MintProtocolServiceFactory() {
    }

    public static MintProtocolService getInstance() {
        return instance;
    }

    public static void setInstance(MintProtocolService service) {
        instance = service;
    }
}
