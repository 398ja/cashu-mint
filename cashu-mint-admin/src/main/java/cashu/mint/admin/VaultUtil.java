package cashu.mint.admin;

import cashu.common.protocol.CashuErrorException;
import cashu.mint.admin.model.MintDto;
import cashu.vault.config.KeyConfiguration;
import cashu.vault.config.KeysetConfiguration;
import cashu.vault.config.MintConfiguration;
import cashu.vault.impl.fs.FSKeyVault;
import cashu.vault.impl.fs.FSKeysetVault;
import cashu.vault.impl.fs.FSMintVault;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;

import java.io.IOException;
import java.io.InputStream;
import java.util.logging.Level;

@Log
@RequiredArgsConstructor
public class VaultUtil {

    private final InputStream mintInputStream;

    @Getter
    private MintDto mint;

    public void createVault() throws IOException, CashuErrorException {
        log.log(Level.INFO, "Creating vault");

        MintIO mintIO = new MintIO();
        mintIO.read(mintInputStream);
        this.mint = mintIO.getMint();
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

    public static void main(String[] args) {
        try {
            InputStream mintInputStream = VaultUtil.class.getResourceAsStream("/mint.json");
            VaultUtil vaultUtil = new VaultUtil(mintInputStream);
            vaultUtil.createVault();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
