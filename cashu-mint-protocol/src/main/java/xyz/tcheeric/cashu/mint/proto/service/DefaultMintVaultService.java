package xyz.tcheeric.cashu.mint.proto.service;

import org.springframework.stereotype.Service;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.vault.api.db.impl.DBMintVault;
import xyz.tcheeric.cashu.vault.db.model.MintEntity;
import java.util.List;

@Service
public class DefaultMintVaultService implements MintVaultService {
    @Override
    public MintEntity retrieveMint(String mintId) throws CashuErrorException {
        return DBMintVault.retrieveMint(mintId).getEntity();
    }

    @Override
    public Mint load(java.util.UUID mintId, boolean withProofs) throws CashuErrorException {
        return DBMintVault.load(mintId, withProofs);
    }

    @Override
    public Mint load(String id, boolean archive) throws CashuErrorException {
        return DBMintVault.load(id, archive);
    }

    @Override
    public List<Mint> load(boolean archive) throws CashuErrorException {
        return DBMintVault.load(archive);
    }
}

