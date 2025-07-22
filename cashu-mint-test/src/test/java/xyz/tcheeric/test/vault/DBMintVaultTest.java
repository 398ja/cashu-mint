package xyz.tcheeric.test.vault;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.common.PrivateKey;
import xyz.tcheeric.cashu.mint.admin.MintUtil;
import xyz.tcheeric.cashu.mint.admin.VaultUtil;
import xyz.tcheeric.cashu.mint.admin.model.KeySetDto;

import java.util.Set;
import java.util.UUID;

import static org.hibernate.validator.internal.util.Contracts.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class DBMintVaultTest {

    private final VaultUtil vaultUtil = new VaultUtil(new MintUtil(UUID.randomUUID().toString(), "sat"));

    public DBMintVaultTest() {
    }

    @BeforeEach
    public void setUp() {
    }

    @AfterEach
    public void tearDown() {
    }

    @Test
    public void getPrivateKey() {
        Set<KeySetDto> keySets = vaultUtil.getMint().getKeySets();
        assertEquals(1, keySets.size());

        KeySetDto keySetDto = keySets.iterator().next();
        int count = keySetDto.getKeys().getValues().size();
        assertEquals(5, count);
        PrivateKey privateKey = keySetDto.getKeys().get(16);
        assertNotNull(privateKey);
    }
}
