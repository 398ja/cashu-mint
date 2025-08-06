package xyz.tcheeric.cashu.mint.proto.tasks;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.common.BlindSignature;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.KeysetId;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.PaymentMethod;
import xyz.tcheeric.cashu.common.PrivateKey;
import xyz.tcheeric.cashu.common.PublicKey;
import xyz.tcheeric.cashu.common.RandomStringSecret;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.crypto.util.Utils;
import xyz.tcheeric.cashu.entities.rest.PostMintQuoteResponse;
import xyz.tcheeric.cashu.entities.rest.PostMintRequest;
import xyz.tcheeric.cashu.entities.rest.PostMintResponse;
import xyz.tcheeric.cashu.gateway.Gateway;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

public class MintTest {

    private static final String VALID_KEYSET_ID = "004cf8cba2f93266";

    @Test
    public void mockMint() throws CashuErrorException {
        Secret secret = RandomStringSecret.fromString("3130c5cd3c69402549fc50df36873251edbeaf7efcec7c618cd8d2955202b518");
        byte[] r = Utils.hexStringToBytes("ea129258e052c096f08d394b40d93ba36e8074728677f0ce11efe1f3e06d2def");
        BlindedMessage blindedMessage = new BlindedMessage(100, KeysetId.fromString(VALID_KEYSET_ID), PublicKey.fromString("02d963e52f9d2f9519f8adedc8517389293d8028e0b33c4bc96b5e3cd128c27af2"), null);

        String quoteId = "61f9b403-3464-489c-97c3-48ca468c099a";
        PostMintRequest postMintRequest = new PostMintRequest(quoteId, List.of(blindedMessage), List.of(secret, secret), List.of(r));

        Gateway mockGateway = Mockito.mock(Gateway.class);
        when(mockGateway.getAmount(anyString())).thenReturn(100);
        when(mockGateway.checkPaymentStatus(anyString())).thenReturn(true);

        MintProtocolService service = Mockito.mock(MintProtocolService.class);
        Mockito.when(service.createGateway(PaymentMethod.MOCK)).thenReturn(mockGateway);
        Mockito.when(service.getPrivateKey(anyString(), anyInt(), any())).thenReturn(
                PrivateKey.fromString("a98675fc698aa718496e533de19d9d6bfb9c3bc9648e6ac9ad8416599881b3b5"));

        MintLoadService mintLoadService = Mockito.mock(MintLoadService.class);
        Mint mint = new Mint();
        when(mintLoadService.load(any(UUID.class), Mockito.anyBoolean())).thenReturn(mint);

        MintTokensTask<Secret> task = new MintTokensTask<>(UUID.randomUUID(), postMintRequest, PaymentMethod.MOCK, mintLoadService, service);

        PostMintResponse response = task.execute();

        assertEquals(1, response.getBlindSignatures().size());

        BlindSignature blindSignature = response.getBlindSignatures().get(0);
        assertEquals(100, blindSignature.getAmount());
        assertEquals("004cf8cba2f93266", blindSignature.getKeySetId().toString());
    }

    @Test
    public void mockMintQuote() {
        Gateway mockGatewayQuote = Mockito.mock(Gateway.class);
        Mockito.when(mockGatewayQuote.createMintQuote(anyInt(), Mockito.isNull())).thenReturn("qid");
        Mockito.when(mockGatewayQuote.getRequest("qid")).thenReturn("req");
        Mockito.when(mockGatewayQuote.getPaymentExpiry("qid")).thenReturn(1);

        MintProtocolService service = Mockito.mock(MintProtocolService.class);
        Mockito.when(service.createGateway(PaymentMethod.BOLT11)).thenReturn(mockGatewayQuote);

        PostMintQuoteResponse postMintQuoteResponse = new MintQuoteTask(100, PaymentMethod.BOLT11, service).execute();

        assertNotNull(postMintQuoteResponse.getQuoteId());
        assertFalse(postMintQuoteResponse.isPaid());
    }

    @Test
    public void mockMintNotPaid() throws CashuErrorException {
        Secret secret = RandomStringSecret.fromString("3130c5cd3c69402549fc50df36873251edbeaf7efcec7c618cd8d2955202b518");
        byte[] r = Utils.hexStringToBytes("ea129258e052c096f08d394b40d93ba36e8074728677f0ce11efe1f3e06d2def");
        BlindedMessage blindedMessage = new BlindedMessage(100, KeysetId.fromString(VALID_KEYSET_ID), PublicKey.fromString("02d963e52f9d2f9519f8adedc8517389293d8028e0b33c4bc96b5e3cd128c27af2"), null);

        String quoteId = "61f9b403-3464-489c-97c3-48ca468c099a";
        PostMintRequest postMintRequest = new PostMintRequest(quoteId, List.of(blindedMessage), List.of(secret, secret), List.of(r));

        Gateway mockGateway = Mockito.mock(Gateway.class);
        when(mockGateway.getAmount(anyString())).thenReturn(100);
        when(mockGateway.checkPaymentStatus(anyString())).thenReturn(false);

        MintProtocolService service = Mockito.mock(MintProtocolService.class);
        Mockito.when(service.createGateway(PaymentMethod.MOCK)).thenReturn(mockGateway);
        Mockito.when(service.getPrivateKey(anyString(), anyInt(), any())).thenReturn(
                PrivateKey.fromString("a98675fc698aa718496e533de19d9d6bfb9c3bc9648e6ac9ad8416599881b3b5"));

        MintLoadService mintLoadService2 = Mockito.mock(MintLoadService.class);
        Mint mint = new Mint();
        when(mintLoadService2.load(any(UUID.class), Mockito.anyBoolean())).thenReturn(mint);
        MintTokensTask<Secret> task = new MintTokensTask<>(UUID.randomUUID(), postMintRequest, PaymentMethod.MOCK, mintLoadService2, service);

        CashuErrorException exception = assertThrows(CashuErrorException.class, task::execute);
        assertEquals("mint_invoice_not_paid_error", exception.getMessage());

    }
}
