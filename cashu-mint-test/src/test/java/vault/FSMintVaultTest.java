package vault;

import cashu.common.util.CashuErrorException;
import cashu.mint.admin.VaultUtil;
import cashu.vault.impl.fs.FSMintVault;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class FSMintVaultTest {

    private final InputStream mintInputStream = FSMintVaultTest.class.getResourceAsStream("/mint.json");
    private final VaultUtil vaultUtil = new VaultUtil(mintInputStream);

    @Before
    public void setUp() throws IOException, CashuErrorException {
        vaultUtil.createVault();
    }

    @After
    public void tearDown() throws CashuErrorException {
        // Clean up
        vaultUtil.deleteVault();
    }

    // Add tests here

    @Test
    public void getPrivateKey() {
        //MintConfiguration mintConfiguration = new MintConfiguration("b921a7fc-c0c1-46c3-bf53-2ee905dd30d9");
        FSMintVault mintVault = new FSMintVault(vaultUtil.getMint().getId());

        String privateKey = mintVault.getPrivateKey("sat", 16);
        assertEquals("a98675fc698aa718496e533de19d9d6bfb9c3bc9648e6ac9ad8416599881b3b5", privateKey);

        assertThrows(RuntimeException.class, () -> {
            mintVault.getPrivateKey("non-existent-unit", 8);
        });
    }
}
