package xyz.tcheeric.cashu.mint.proto.tasks;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.KeysetId;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.PrivateKey;
import xyz.tcheeric.cashu.common.PublicKey;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.crypto.BDHKEUtils;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;

public class SignBlindedMessage33ByteTest {

    // Verifies that when BDHKE returns a 33-byte blind signature (compressed point),
    // SignBlindedMessageTask converts it to hex and builds a Signature via fromString (66 hex chars).
    @Test
    public void signReturns33ByteSignatureHandled() throws CashuErrorException {
        // Arrange
        BlindedMessage blindedMessage = new BlindedMessage();
        blindedMessage.setAmount(8);
        blindedMessage.setKeySetId(KeysetId.fromString("004cf8cba2f93266"));
        blindedMessage.setBlindedMessage(PublicKey.fromString("02d963e52f9d2f9519f8adedc8517389293d8028e0b33c4bc96b5e3cd128c27af2"));

        MintProtocolService service = Mockito.mock(MintProtocolService.class);
        Mockito.when(service.getPrivateKey(anyString(), anyInt(), any())).thenReturn(
                PrivateKey.fromString("a98675fc698aa718496e533de19d9d6bfb9c3bc9648e6ac9ad8416599881b3b5"));

        byte[] thirtyThree = new byte[33];
        for (int i = 0; i < thirtyThree.length; i++) {
            thirtyThree[i] = (byte) i;
        }

        Mint mint = new Mint();
        SignBlindedMessageTask task = new SignBlindedMessageTask(mint, blindedMessage, service);

        try (MockedStatic<BDHKEUtils> mocked = Mockito.mockStatic(BDHKEUtils.class)) {
            mocked.when(() -> BDHKEUtils.signBlindedMessage(
                    Mockito.<byte[]>any(byte[].class),
                    Mockito.<byte[]>any(byte[].class)
            )).thenReturn(thirtyThree);

            // Act
            var blindSignature = task.execute();

            // Assert
            assertNotNull(blindSignature.getBlindedSignature());
            String sigHex = blindSignature.getBlindedSignature().toString();
            assertEquals(66, sigHex.length());
        }
    }
}
