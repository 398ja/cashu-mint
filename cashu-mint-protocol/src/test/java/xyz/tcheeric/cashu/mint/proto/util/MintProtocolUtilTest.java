package xyz.tcheeric.cashu.mint.proto.util;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.web.client.HttpClientErrorException;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.vault.api.VaultClientFactory;
import xyz.tcheeric.cashu.vault.db.client.KeySetVaultClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class MintProtocolUtilTest {

    // Ensures that a 404 from the vault yields a structured keyset_not_found Cashu error.
    @Test
    public void getPrivateKeyWrapsVaultNotFound() {
        Mint mint = new Mint();

        try (MockedStatic<VaultClientFactory> vaultFactory = Mockito.mockStatic(VaultClientFactory.class)) {
            KeySetVaultClient keySetClient = Mockito.mock(KeySetVaultClient.class);
            vaultFactory.when(VaultClientFactory::keySetClient).thenReturn(keySetClient);

            HttpClientErrorException.NotFound notFound = Mockito.mock(HttpClientErrorException.NotFound.class);
            Mockito.when(keySetClient.getByKeySetId(Mockito.anyString())).thenThrow(notFound);

            CashuErrorException ex = assertThrows(
                    CashuErrorException.class,
                    () -> MintProtocolUtil.getPrivateKey("abc", 1, mint)
            );
            assertEquals("keyset_not_found", ex.getMessage());
        }
    }
}
