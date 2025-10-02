package xyz.tcheeric.cashu.mint.proto.service.impl;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.vault.api.db.impl.DBMintVault;

import java.util.List;
import java.util.UUID;

@Service
@Profile({"!dev", "!test"})
public class DefaultMintLoadService implements MintLoadService {
    @Override
    public Mint load(UUID mintId, boolean archive) throws CashuErrorException {
        return DBMintVault.load(mintId, archive);
    }

    @Override
    public List<Mint> load(boolean archive) throws CashuErrorException {
        return DBMintVault.load(archive);
    }
}
