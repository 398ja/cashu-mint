package protocol.tasks;

import cashu.common.model.BlindSignature;
import cashu.common.model.BlindedMessage;
import cashu.common.model.Mint;
import cashu.common.model.PrivateKey;
import cashu.common.model.PublicKey;
import cashu.common.protocol.CashuErrorException;
import cashu.mint.proto.tasks.SignBlindedMessageTask;
import cashu.mint.admin.model.MintDto;
import cashu.mint.proto.util.MintUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import cashu.mint.admin.VaultUtil;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.spy;

public class SignBlindedMessageTest {

    private VaultUtil vaultUtil;

    @Before
    public void setUp() throws IOException, CashuErrorException {
        vaultUtil = new VaultUtil(getClass().getResourceAsStream("/mint.json"));
        vaultUtil.createVault();
    }

    @After
    public void tearDown() throws CashuErrorException {
        vaultUtil.deleteVault();
    }

    @Test
    public void sign() {
        BlindedMessage blindedMessage = new BlindedMessage();
        blindedMessage.setAmount(16);
        blindedMessage.setKeySetId("004cf8cba2f93266");
        blindedMessage.setBlindedMessage(PublicKey.fromString("02d963e52f9d2f9519f8adedc8517389293d8028e0b33c4bc96b5e3cd128c27af2"));

        var mintDto = spy(vaultUtil.getMint());

        Mint mint = MintDto.toMint(mintDto);
        SignBlindedMessageTask task = new SignBlindedMessageTask(mint, blindedMessage);

        try (MockedStatic<MintUtil> mintUtil = Mockito.mockStatic(MintUtil.class)) {
            mintUtil.when(() -> MintUtil.getPrivateKey(anyString(), anyInt(), any()))
                    .thenReturn(PrivateKey.fromString("a98675fc698aa718496e533de19d9d6bfb9c3bc9648e6ac9ad8416599881b3b5"));

            BlindSignature signature = task.execute();

            assertNotNull(signature);
            assertEquals(16, signature.getAmount());
            assertEquals("004cf8cba2f93266", signature.getKeySetId());
            assertNotNull(signature.getBlindedSignature());
        }
    }
}
