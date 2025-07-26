package xyz.tcheeric.cashu.mint.proto.service;

import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.util.CashuErrorException;

import java.util.UUID;

public interface MintLoadService {
    Mint load(UUID mintId, boolean archive) throws CashuErrorException;
}
