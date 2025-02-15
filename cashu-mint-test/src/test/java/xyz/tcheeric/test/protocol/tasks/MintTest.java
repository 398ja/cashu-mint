package xyz.tcheeric.test.protocol.tasks;

import cashu.util.Utils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.common.model.BlindSignature;
import xyz.tcheeric.cashu.common.model.BlindedMessage;
import xyz.tcheeric.cashu.common.model.Mint;
import xyz.tcheeric.cashu.common.model.PaymentMethod;
import xyz.tcheeric.cashu.common.model.PrivateKey;
import xyz.tcheeric.cashu.common.model.PublicKey;
import xyz.tcheeric.cashu.common.model.RandomStringSecret;
import xyz.tcheeric.cashu.common.model.Secret;
import xyz.tcheeric.cashu.common.model.rest.PostMintQuoteBolt11Request;
import xyz.tcheeric.cashu.common.model.rest.PostMintQuoteResponse;
import xyz.tcheeric.cashu.common.model.rest.PostMintRequest;
import xyz.tcheeric.cashu.common.model.rest.PostMintResponse;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.gateway.Gateway;
import xyz.tcheeric.cashu.mint.admin.VaultUtil;
import xyz.tcheeric.cashu.mint.admin.model.MintDto;
import xyz.tcheeric.cashu.mint.proto.nut.NUT04;
import xyz.tcheeric.cashu.mint.proto.tasks.MintTask;
import xyz.tcheeric.cashu.mint.proto.util.MintProtocolUtil;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

public class MintTest {

    private VaultUtil vaultUtil;

    @BeforeEach
    public void setUp() throws IOException, CashuErrorException {
        vaultUtil = new VaultUtil(getClass().getResourceAsStream("/mint.json"));
        vaultUtil.createVault();
    }

    @AfterEach
    public void tearDown() throws CashuErrorException {
        vaultUtil.deleteVault();
    }

    @Test
    public void mockMint() throws CashuErrorException {
        Secret secret = RandomStringSecret.fromString("3130c5cd3c69402549fc50df36873251edbeaf7efcec7c618cd8d2955202b518");
        byte[] r = Utils.hexStringToBytes("ea129258e052c096f08d394b40d93ba36e8074728677f0ce11efe1f3e06d2def");
        BlindedMessage blindedMessage = new BlindedMessage(100, "004cf8cba2f93266", PublicKey.fromString("02d963e52f9d2f9519f8adedc8517389293d8028e0b33c4bc96b5e3cd128c27af2"), null);

        String quoteId = "61f9b403-3464-489c-97c3-48ca468c099a";
        PostMintRequest postMintRequest = new PostMintRequest(quoteId, List.of(blindedMessage), List.of(secret, secret), List.of(r));

        Gateway mockGateway = Mockito.mock(Gateway.class);
        when(mockGateway.getAmount(anyString())).thenReturn(100);
        when(mockGateway.checkPaymentStatus(anyString())).thenReturn(true);

        Mint mint = MintDto.toMint(vaultUtil.getMint());
        MintTask task = new MintTask(postMintRequest, PaymentMethod.MOCK, mint);

        try (MockedStatic<MintProtocolUtil> mintUtil = Mockito.mockStatic(MintProtocolUtil.class)) {
            mintUtil.when(() -> MintProtocolUtil.getPrivateKey(anyString(), anyInt(), any()))
                    .thenReturn(PrivateKey.fromString("a98675fc698aa718496e533de19d9d6bfb9c3bc9648e6ac9ad8416599881b3b5"));
            mintUtil.when(() -> MintProtocolUtil.createGateway(PaymentMethod.MOCK))
                    .thenReturn(mockGateway);

            PostMintResponse response = task.execute();

            assertEquals(1, response.getBlindSignatures().size());

            BlindSignature blindSignature = response.getBlindSignatures().get(0);
            assertEquals(100, blindSignature.getAmount());
            assertEquals("004cf8cba2f93266", blindSignature.getKeySetId());
        }
    }

    @Test
    public void mintQuote() {

        PostMintQuoteBolt11Request postMintQuoteBolt11Request = new PostMintQuoteBolt11Request();
        postMintQuoteBolt11Request.setAmount(100);
        postMintQuoteBolt11Request.setUnit("sat");
        System.setProperty("wid", "A1b2C3d4");
        PostMintQuoteResponse postMintQuoteResponse = NUT04.quote(100, PaymentMethod.BOLT11);

        assertNotNull(postMintQuoteResponse.getQuoteId());
        assertFalse(postMintQuoteResponse.isPaid());

    }

    @Test
    public void mockMintNotPaid() {
        Secret secret = RandomStringSecret.fromString("3130c5cd3c69402549fc50df36873251edbeaf7efcec7c618cd8d2955202b518");
        byte[] r = Utils.hexStringToBytes("ea129258e052c096f08d394b40d93ba36e8074728677f0ce11efe1f3e06d2def");
        BlindedMessage blindedMessage = new BlindedMessage(100, "004cf8cba2f93266", PublicKey.fromString("02d963e52f9d2f9519f8adedc8517389293d8028e0b33c4bc96b5e3cd128c27af2"), null);

        String quoteId = "61f9b403-3464-489c-97c3-48ca468c099a";
        PostMintRequest postMintRequest = new PostMintRequest(quoteId, List.of(blindedMessage), List.of(secret, secret), List.of(r));

        Gateway mockGateway = Mockito.mock(Gateway.class);
        when(mockGateway.getAmount(anyString())).thenReturn(100);
        when(mockGateway.checkPaymentStatus(anyString())).thenReturn(false);

        Mint mint = MintDto.toMint(vaultUtil.getMint());
        MintTask task = new MintTask(postMintRequest, PaymentMethod.MOCK, mint);

        try (MockedStatic<MintProtocolUtil> mintUtil = Mockito.mockStatic(MintProtocolUtil.class)) {
            mintUtil.when(() -> MintProtocolUtil.getPrivateKey(anyString(), anyInt(), any()))
                    .thenReturn(PrivateKey.fromString("a98675fc698aa718496e533de19d9d6bfb9c3bc9648e6ac9ad8416599881b3b5"));
            mintUtil.when(() -> MintProtocolUtil.createGateway(PaymentMethod.MOCK))
                    .thenReturn(mockGateway);

            // Assert that a CashuErrorException is thrown
            CashuErrorException exception = assertThrows(CashuErrorException.class, task::execute);
            assertEquals("mint_invoice_not_paid_error", exception.getMessage());
        }

    }
}
