package xyz.tcheeric.cashu.mint.proto.service;

import org.springframework.stereotype.Service;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.vault.api.db.impl.DBMintVault;
import xyz.tcheeric.cashu.vault.db.model.MintEntity;

@Service
public class DefaultMintVaultService implements MintVaultService {
    @Override
    public MintEntity retrieveMint(String mintId) throws CashuErrorException {
        return DBMintVault.retrieveMint(mintId).getEntity();
    }
}
