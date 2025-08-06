package xyz.tcheeric.cashu.mint.proto.service;

import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.vault.db.model.MintEntity;

public interface MintVaultService {
    MintEntity retrieveMint(String mintId) throws CashuErrorException;

    void load(MintEntity mintEntity, boolean archive, boolean create) throws CashuErrorException;
}
