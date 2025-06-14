package xyz.tcheeric.test.vault;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.mint.admin.VaultUtil;
import xyz.tcheeric.cashu.vault.impl.fs.FSMintVault;
import xyz.tcheeric.test.MintUtilTest;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class FSMintVaultTest {

    private final VaultUtil vaultUtil = new VaultUtil(new MintUtilTest(UUID.randomUUID().toString(), "sat"));

    public FSMintVaultTest() throws Exception {
    }

    @BeforeEach
    public void setUp() throws Exception {
        vaultUtil.createVault();
    }

    @AfterEach
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
