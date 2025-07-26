package xyz.tcheeric.cashu.mint.proto.service;

import org.springframework.stereotype.Service;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.vault.api.db.impl.DBMintVault;

import java.util.UUID;

@Service
public class DefaultMintLoadService implements MintLoadService {
    @Override
    public Mint load(UUID mintId, boolean archive) throws CashuErrorException {
        return DBMintVault.load(mintId, archive);
    }
}
