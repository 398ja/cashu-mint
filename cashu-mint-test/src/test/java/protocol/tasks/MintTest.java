package protocol.tasks;

import cashu.common.model.BlindSignature;
import cashu.common.model.BlindedMessage;
import cashu.common.model.Mint;
import cashu.common.model.PaymentMethod;
import cashu.common.model.PrivateKey;
import cashu.common.model.PublicKey;
import cashu.common.model.Secret;
import cashu.common.model.rest.PostMintRequest;
import cashu.common.model.rest.PostMintResponse;
import cashu.common.protocol.CashuErrorException;
import cashu.mint.proto.abilities.tasks.MintTask;
import cashu.mint.admin.model.MintDto;
import cashu.mint.gateway.Gateway;
import cashu.mint.proto.util.MintUtil;
import cashu.util.Utils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import cashu.mint.admin.VaultUtil;

import java.io.IOException;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

public class MintTest {

    private VaultUtil vaultUtil;

    @Before
    public void setUp() throws IOException, CashuErrorException  {
        vaultUtil = new VaultUtil(getClass().getResourceAsStream("/mint.json"));
        vaultUtil.createVault();
    }

    @After
    public void tearDown() throws CashuErrorException {
        vaultUtil.deleteVault();
    }

    @Test
    public void mint() throws CashuErrorException {
        Secret secret = Secret.fromString("3130c5cd3c69402549fc50df36873251edbeaf7efcec7c618cd8d2955202b518");
        byte[] r = Utils.hexStringToBytes("ea129258e052c096f08d394b40d93ba36e8074728677f0ce11efe1f3e06d2def");
        BlindedMessage blindedMessage = new BlindedMessage(100, "004cf8cba2f93266", PublicKey.fromString("02d963e52f9d2f9519f8adedc8517389293d8028e0b33c4bc96b5e3cd128c27af2"));

        String quoteId = "61f9b403-3464-489c-97c3-48ca468c099a";
        PostMintRequest postMintRequest = new PostMintRequest(quoteId, List.of(blindedMessage), List.of(secret, secret), List.of(r));

        Gateway mockGateway = Mockito.mock(Gateway.class);
        when(mockGateway.getAmount(anyString())).thenReturn(100);
        when(mockGateway.checkPaymentStatus(anyString())).thenReturn(true);

        Mint mint = MintDto.toMint(vaultUtil.getMint());
        MintTask task = new MintTask(postMintRequest, PaymentMethod.MOCK, mint);

        try (MockedStatic<MintUtil> mintUtil = Mockito.mockStatic(MintUtil.class)) {
            mintUtil.when(() -> MintUtil.getPrivateKey(anyString(), anyInt(), any()))
                    .thenReturn(PrivateKey.fromString("a98675fc698aa718496e533de19d9d6bfb9c3bc9648e6ac9ad8416599881b3b5"));
            mintUtil.when(() -> MintUtil.createGateway(PaymentMethod.MOCK, "mint"))
                    .thenReturn(mockGateway);

            PostMintResponse response = task.execute();

            assertEquals(1, response.getBlindSignatures().size());

            BlindSignature blindSignature = response.getBlindSignatures().get(0);
            assertEquals(100, blindSignature.getAmount());
            assertEquals("004cf8cba2f93266", blindSignature.getKeySetId());
        }
    }

    @Test
    public void mintNotPaid() {
        Secret secret = Secret.fromString("3130c5cd3c69402549fc50df36873251edbeaf7efcec7c618cd8d2955202b518");
        byte[] r = Utils.hexStringToBytes("ea129258e052c096f08d394b40d93ba36e8074728677f0ce11efe1f3e06d2def");
        BlindedMessage blindedMessage = new BlindedMessage(100, "004cf8cba2f93266", PublicKey.fromString("02d963e52f9d2f9519f8adedc8517389293d8028e0b33c4bc96b5e3cd128c27af2"));

        String quoteId = "61f9b403-3464-489c-97c3-48ca468c099a";
        PostMintRequest postMintRequest = new PostMintRequest(quoteId, List.of(blindedMessage), List.of(secret, secret), List.of(r));

        Gateway mockGateway = Mockito.mock(Gateway.class);
        when(mockGateway.getAmount(anyString())).thenReturn(100);
        when(mockGateway.checkPaymentStatus(anyString())).thenReturn(false);

        Mint mint = MintDto.toMint(vaultUtil.getMint());
        MintTask task = new MintTask(postMintRequest, PaymentMethod.MOCK, mint);

        try (MockedStatic<MintUtil> mintUtil = Mockito.mockStatic(MintUtil.class)) {
            mintUtil.when(() -> MintUtil.getPrivateKey(anyString(), anyInt(), any()))
                    .thenReturn(PrivateKey.fromString("a98675fc698aa718496e533de19d9d6bfb9c3bc9648e6ac9ad8416599881b3b5"));
            mintUtil.when(() -> MintUtil.createGateway(PaymentMethod.MOCK, "mint"))
                    .thenReturn(mockGateway);

            // Assert that a CashuErrorException is thrown
            CashuErrorException exception = assertThrows(CashuErrorException.class, task::execute);
            assertEquals("mint_invoice_not_paid_error", exception.getMessage());
        }

    }
}
