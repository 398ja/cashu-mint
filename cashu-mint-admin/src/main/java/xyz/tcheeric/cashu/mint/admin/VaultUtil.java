package xyz.tcheeric.cashu.mint.admin;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.mint.admin.model.MintDto;
import xyz.tcheeric.cashu.vault.config.KeyConfiguration;
import xyz.tcheeric.cashu.vault.config.KeysetConfiguration;
import xyz.tcheeric.cashu.vault.config.MintConfiguration;
import xyz.tcheeric.cashu.vault.impl.fs.FSKeyVault;
import xyz.tcheeric.cashu.vault.impl.fs.FSKeysetVault;
import xyz.tcheeric.cashu.vault.impl.fs.FSMintVault;

import java.util.logging.Level;

@Log
@RequiredArgsConstructor
public class VaultUtil {

/*
    private final String mintId;
    private final String unit;
*/
    private final MintUtil mintUtil;

    @Getter
    private MintDto mint;

    public void createVault() throws Exception {
        log.log(Level.INFO, "Creating vault");

        //MintUtil mintUtil = new MintUtil(mintId, unit);
        mintUtil.write();
        this.mint = mintUtil.getMint();
        log.log(Level.INFO, "MintDto read: {0}", mint.getId());

        MintConfiguration mintConfiguration = new MintConfiguration(mint.getId());
        FSMintVault mintVault = new FSMintVault(mintConfiguration);
        mintVault.store();

        mint.getKeySets().forEach(keySet -> {
            KeysetConfiguration keysetConfiguration = new KeysetConfiguration(mintConfiguration, keySet.getId(), keySet.getUnit());
            FSKeysetVault keysetVault = new FSKeysetVault(keysetConfiguration);
            try {
                keysetVault.store();
            } catch (CashuErrorException e) {
                throw new RuntimeException(e);
            }

            keySet.getKeys().getValues().keySet().forEach(key -> {
                KeyConfiguration keyConfiguration = new KeyConfiguration(keysetConfiguration, key, keySet.getKeys().getValues().get(key).toString());
                FSKeyVault keyVault = new FSKeyVault(keyConfiguration);
                try {
                    keyVault.store();
                } catch (CashuErrorException e) {
                    throw new RuntimeException(e);
                }
            });
        });
    }

    public void archiveVault() throws CashuErrorException {
        log.log(Level.INFO, "Archiving vault");

        String mintId = this.mint.getId();

        MintConfiguration mintConfiguration = new MintConfiguration(mintId);
        FSMintVault mintVault = new FSMintVault(mintConfiguration);
        mintVault.archive(mintId);
    }

    public void deleteVault() throws CashuErrorException {
        log.log(Level.INFO, "Deleting vault");

        String mintId = this.mint.getId();

        MintConfiguration mintConfiguration = new MintConfiguration(mintId);
        FSMintVault mintVault = new FSMintVault(mintConfiguration);
        mintVault.delete();
    }
}
