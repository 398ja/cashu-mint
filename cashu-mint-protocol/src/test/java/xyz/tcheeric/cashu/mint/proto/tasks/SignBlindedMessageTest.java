package xyz.tcheeric.cashu.mint.proto.tasks;

import org.junit.jupiter.api.Test;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;

public class SignBlindedMessageTest {

    private static final String VALID_KEYSET_ID = "004cf8cba2f93266";

    @Test
    public void sign() throws CashuErrorException {
        BlindedMessage blindedMessage = new BlindedMessage();
        blindedMessage.setAmount(16);
        blindedMessage.setKeySetId(KeysetId.fromString(VALID_KEYSET_ID));
        blindedMessage.setBlindedMessage(PublicKey.fromString("02d963e52f9d2f9519f8adedc8517389293d8028e0b33c4bc96b5e3cd128c27af2"));

        MintProtocolService service = Mockito.mock(MintProtocolService.class);
        Mockito.when(service.getPrivateKey(anyString(), anyInt(), any())).thenReturn(
                PrivateKey.fromString("a98675fc698aa718496e533de19d9d6bfb9c3bc9648e6ac9ad8416599881b3b5"));
        Mockito.when(service.getPrivateKeyForSigning(anyString(), anyInt(), any())).thenReturn(
                PrivateKey.fromString("a98675fc698aa718496e533de19d9d6bfb9c3bc9648e6ac9ad8416599881b3b5"));

        Mint mint = new Mint();
        SignBlindedMessageTask task = new SignBlindedMessageTask(mint, blindedMessage, service, new DefaultSignatureVaultService());

        BlindSignature signature = task.execute();

        assertNotNull(signature);
        assertEquals(16, signature.getAmount());
        assertEquals("004cf8cba2f93266", signature.getKeySetId().toString());
        assertNotNull(signature.getBlindedSignature());
    }

    @Test
    public void signNoPrivateKey() throws CashuErrorException {
        BlindedMessage blindedMessage = new BlindedMessage();
        blindedMessage.setAmount(16);
        blindedMessage.setKeySetId(KeysetId.fromString(VALID_KEYSET_ID));
        blindedMessage.setBlindedMessage(PublicKey.fromString("02d963e52f9d2f9519f8adedc8517389293d8028e0b33c4bc96b5e3cd128c27af2"));

        MintProtocolService service = Mockito.mock(MintProtocolService.class);
        Mockito.when(service.getPrivateKey(anyString(), anyInt(), any())).thenReturn(null);
        Mockito.when(service.getPrivateKeyForSigning(anyString(), anyInt(), any())).thenReturn(null);

        Mint mint = new Mint();
        SignBlindedMessageTask task = new SignBlindedMessageTask(mint, blindedMessage, service, new DefaultSignatureVaultService());

        assertThrows(CashuErrorException.class, task::execute);
    }
}
