package xyz.tcheeric.cashu.mint.proto.tasks;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.common.BlindSignature;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.KeysetId;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.PrivateKey;
import xyz.tcheeric.cashu.common.PublicKey;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.impl.DefaultSignatureVaultService;
import xyz.tcheeric.cashu.mint.proto.util.MintProtocolUtil;
import xyz.tcheeric.cashu.vault.api.VaultClientFactory;
import xyz.tcheeric.cashu.vault.db.client.KeySetVaultClient;

import org.springframework.web.client.HttpClientErrorException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

public class SignBlindedMessageTest {

    private static final String VALID_KEYSET_ID = "004cf8cba2f93266";

    // Ensures that a blinded message is signed when the private key is present in the vault.
    @Test
    public void sign() throws CashuErrorException {
        BlindedMessage blindedMessage = new BlindedMessage();
        blindedMessage.setAmount(16);
        blindedMessage.setKeySetId(KeysetId.fromString(VALID_KEYSET_ID));
        blindedMessage.setBlindedMessage(PublicKey.fromString("02d963e52f9d2f9519f8adedc8517389293d8028e0b33c4bc96b5e3cd128c27af2"));

        MintProtocolService service = Mockito.mock(MintProtocolService.class);
        Mockito.when(service.getPrivateKey(anyString(), anyInt(), any())).thenReturn(
                PrivateKey.fromString("a98675fc698aa718496e533de19d9d6bfb9c3bc9648e6ac9ad8416599881b3b5"));

        Mint mint = new Mint();
        SignBlindedMessageTask task = new SignBlindedMessageTask(mint, blindedMessage, service, new DefaultSignatureVaultService());

        BlindSignature signature = task.execute();

        assertNotNull(signature);
        assertEquals(16, signature.getAmount());
        assertEquals("004cf8cba2f93266", signature.getKeySetId().toString());
        assertNotNull(signature.getBlindedSignature());
    }

    // Ensures that a missing private key results in a CashuErrorException from the task.
    @Test
    public void signNoPrivateKey() throws CashuErrorException {
        BlindedMessage blindedMessage = new BlindedMessage();
        blindedMessage.setAmount(16);
        blindedMessage.setKeySetId(KeysetId.fromString(VALID_KEYSET_ID));
        blindedMessage.setBlindedMessage(PublicKey.fromString("02d963e52f9d2f9519f8adedc8517389293d8028e0b33c4bc96b5e3cd128c27af2"));

        MintProtocolService service = Mockito.mock(MintProtocolService.class);
        Mockito.when(service.getPrivateKey(anyString(), anyInt(), any())).thenReturn(null);

        Mint mint = new Mint();
        SignBlindedMessageTask task = new SignBlindedMessageTask(mint, blindedMessage, service, new DefaultSignatureVaultService());

        assertThrows(CashuErrorException.class, task::execute);
    }

    // Ensures that a vault 404 propagates as a structured keyset_not_found Cashu error.
    @Test
    public void signVaultNotFoundWrapsIntoCashuError() throws CashuErrorException {
        BlindedMessage blindedMessage = new BlindedMessage();
        blindedMessage.setAmount(8);
        blindedMessage.setKeySetId(KeysetId.fromString(VALID_KEYSET_ID));
        blindedMessage.setBlindedMessage(PublicKey.fromString("02d963e52f9d2f9519f8adedc8517389293d8028e0b33c4bc96b5e3cd128c27af2"));

        Mint mint = new Mint();
        MintProtocolService service = Mockito.mock(MintProtocolService.class);
        Mockito.when(service.getPrivateKey(anyString(), anyInt(), any(Mint.class)))
                .thenAnswer(invocation -> MintProtocolUtil.getPrivateKey(
                        invocation.getArgument(0, String.class),
                        invocation.getArgument(1, Integer.class),
                        invocation.getArgument(2, Mint.class)
                ));

        try (MockedStatic<VaultClientFactory> vaultFactory = Mockito.mockStatic(VaultClientFactory.class)) {
            KeySetVaultClient keySetClient = Mockito.mock(KeySetVaultClient.class);
            vaultFactory.when(VaultClientFactory::keySetClient).thenReturn(keySetClient);

            HttpClientErrorException.NotFound notFound = Mockito.mock(HttpClientErrorException.NotFound.class);
            Mockito.when(keySetClient.getByKeySetId(eq(VALID_KEYSET_ID))).thenThrow(notFound);

            SignBlindedMessageTask task = new SignBlindedMessageTask(mint, blindedMessage, service, new DefaultSignatureVaultService());

            CashuErrorException ex = assertThrows(CashuErrorException.class, task::execute);
            assertEquals("keyset_not_found", ex.getMessage());
        }
    }
}
