package xyz.tcheeric.cashu.mint.proto.service;

import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.util.CashuErrorException;

import java.util.UUID;

import java.util.List;

public interface MintLoadService {
    Mint load(UUID mintId, boolean archive) throws CashuErrorException;

    default Mint load(String mintId, boolean archive) throws CashuErrorException {
        return load(UUID.fromString(mintId), archive);
    }

    List<Mint> load(boolean archive) throws CashuErrorException;
}
