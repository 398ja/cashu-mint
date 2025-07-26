package xyz.tcheeric.cashu.mint.proto.service;

import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.vault.db.model.MintEntity;

import xyz.tcheeric.cashu.common.Mint;
import java.util.List;

public interface MintVaultService {
    MintEntity retrieveMint(String mintId) throws CashuErrorException;

    Mint load(java.util.UUID mintId, boolean withProofs) throws CashuErrorException;

    Mint load(String id, boolean archive) throws CashuErrorException;

    List<Mint> load(boolean archive) throws CashuErrorException;
}
