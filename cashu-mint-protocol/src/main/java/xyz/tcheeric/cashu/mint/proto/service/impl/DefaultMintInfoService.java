package xyz.tcheeric.cashu.mint.proto.service.impl;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import xyz.tcheeric.cashu.mint.proto.service.MintInfoService;
import xyz.tcheeric.cashu.mint.proto.util.MintInfo;

@Service
public class DefaultMintInfoService implements MintInfoService {

    private final MintInfo mintInfo;

    @Autowired
    public DefaultMintInfoService(MintInfo mintInfo) {
        this.mintInfo = mintInfo;
    }

    @Override
    public MintInfo getMintInfo() {
        return mintInfo;
    }
}
