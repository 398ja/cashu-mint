package xyz.tcheeric.cashu.mint.admin;

import lombok.Getter;
import lombok.extern.java.Log;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.mint.admin.model.MintDto;
import xyz.tcheeric.cashu.vault.api.config.MintConfiguration;
import xyz.tcheeric.cashu.vault.api.db.impl.DBMintVault;

import java.util.logging.Level;

@Log
public class VaultUtil {

    private final MintUtil mintUtil;

    @Getter
    private MintDto mint;

    public VaultUtil(MintUtil mintUtil) {
        this.mintUtil = mintUtil;
        this.mint = mintUtil.getMint();
    }

    public void createVault() throws Exception {
        log.log(Level.INFO, "Creating vault");

        //MintUtil mintUtil = new MintUtil(mintId, unit);
        mintUtil.write();
        this.mint = mintUtil.getMint();
        log.log(Level.INFO, "MintDto read: {0}", mint.getId());

/*
        MintConfiguration mintConfiguration = new MintConfiguration(mint.getId());
        DBMintVault mintVault = new DBMintVault(mintConfiguration);
        mintVault.store();

        mint.getKeySets().forEach(keySet -> {
            KeysetConfiguration keysetConfiguration = new KeysetConfiguration(mintConfiguration, keySet.getId(), keySet.getUnit());
            DBKeySetVault keysetVault = new DBKeySetVault(keysetConfiguration);
            keysetVault.store();

            keySet.getKeys().getValues().keySet().forEach(key -> {
                KeyConfiguration keyConfiguration = new KeyConfiguration(keysetConfiguration, key, keySet.getKeys().getValues().get(key).toString());
                DBKeyVault keyVault = new DBKeyVault(keyConfiguration);
                keyVault.store();
            });
        });
*/
    }

    public void archiveVault() throws CashuErrorException {
        log.log(Level.INFO, "Archiving vault");

        String mintId = this.mint.getId();

        MintConfiguration mintConfiguration = new MintConfiguration(mintId);
        DBMintVault mintVault = new DBMintVault(mintConfiguration);
        mintVault.archive();
    }

    public void deleteVault() throws CashuErrorException {
        log.log(Level.INFO, "Deleting vault");

        String mintId = this.mint.getId();

        MintConfiguration mintConfiguration = new MintConfiguration(mintId);
        DBMintVault mintVault = new DBMintVault(mintConfiguration);
        mintVault.delete();
    }
}
