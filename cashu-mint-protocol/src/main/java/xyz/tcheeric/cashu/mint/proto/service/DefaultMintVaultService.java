package xyz.tcheeric.cashu.mint.proto.service;

import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.vault.api.db.impl.DBMintVault;
import xyz.tcheeric.cashu.vault.db.model.MintEntity;

public class DefaultMintVaultService implements MintVaultService {
    @Override
    public MintEntity getMint(String mintId) throws CashuErrorException {
        return DBMintVault.retrieveMint(mintId).getEntity();
    }
}
