package xyz.tcheeric.cashu.mint.proto.tasks;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.KeySet;
import xyz.tcheeric.cashu.common.Keys;
import xyz.tcheeric.cashu.common.KeysetId;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.nut18.PaymentMethod;
import xyz.tcheeric.cashu.common.PrivateKey;
import xyz.tcheeric.cashu.common.PublicKey;
import xyz.tcheeric.cashu.common.RandomStringSecret;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.crypto.util.Utils;
import xyz.tcheeric.cashu.entities.rest.nut04.PostMintQuoteResponse;
import xyz.tcheeric.cashu.entities.rest.nut04.PostMintRequest;
import xyz.tcheeric.cashu.entities.rest.nut04.PostMintResponse;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.impl.DefaultSignatureVaultService;
import xyz.tcheeric.payment.adapter.core.common.Gateway;

import java.math.BigInteger;
import java.util.List;
import java.util.UUID;
import xyz.tcheeric.cashu.common.nut00.CashuErrorCode;

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

    // Ensures minting succeeds and returns signatures when invoice is paid
    @Test
    public void mockMint() throws CashuErrorException {
        Secret secret = RandomStringSecret.fromString("3130c5cd3c69402549fc50df36873251edbeaf7efcec7c618cd8d2955202b518");
        byte[] r = Utils.hexStringToBytes("ea129258e052c096f08d394b40d93ba36e8074728677f0ce11efe1f3e06d2def");
        // Split 100 into proper denominations: 64 + 32 + 4 = 100
        // Each blinded message must have a unique public key so the signature vault stores them independently
        BlindedMessage blindedMessage1 = new BlindedMessage(64, KeysetId.fromString(VALID_KEYSET_ID), PublicKey.fromString("02d963e52f9d2f9519f8adedc8517389293d8028e0b33c4bc96b5e3cd128c27af2"), null);
        BlindedMessage blindedMessage2 = new BlindedMessage(32, KeysetId.fromString(VALID_KEYSET_ID), PublicKey.fromString("03a0434d9e47f3c86235477c7b1ae6ae5d3442d49b1943c2b752a68e2a47e247c7"), null);
        BlindedMessage blindedMessage3 = new BlindedMessage(4, KeysetId.fromString(VALID_KEYSET_ID), PublicKey.fromString("025f9d298d8d9e774c81ee64927a27e6e6b6e18f65447eb6a16808f92b84e44112"), null);

        String quoteId = "61f9b403-3464-489c-97c3-48ca468c099a";
        PostMintRequest postMintRequest = new PostMintRequest(quoteId, List.of(blindedMessage1, blindedMessage2, blindedMessage3), List.of(secret, secret), List.of(r));

        Gateway mockGateway = Mockito.mock(Gateway.class);
        when(mockGateway.getAmount(anyString())).thenReturn(100);
        when(mockGateway.checkPaymentStatus(anyString())).thenReturn(true);

        MintProtocolService service = Mockito.mock(MintProtocolService.class);
        Mockito.when(service.createGateway(PaymentMethod.MOCK)).thenReturn(mockGateway);
        Mockito.when(service.getPrivateKey(anyString(), anyInt(), any())).thenReturn(
                PrivateKey.fromString("a98675fc698aa718496e533de19d9d6bfb9c3bc9648e6ac9ad8416599881b3b5"));
        Mockito.when(service.getPrivateKeyForSigning(anyString(), anyInt(), any())).thenReturn(
                PrivateKey.fromString("a98675fc698aa718496e533de19d9d6bfb9c3bc9648e6ac9ad8416599881b3b5"));

        MintLoadService mintLoadService = Mockito.mock(MintLoadService.class);
        Mint mint = new Mint();
        // Create keys with standard Cashu denominations (powers of 2)
        Keys keys = new Keys();
        keys.put(BigInteger.valueOf(1), PrivateKey.derivePublicKey(PrivateKey.fromString("0000000000000000000000000000000000000000000000000000000000000001")));
        keys.put(BigInteger.valueOf(2), PrivateKey.derivePublicKey(PrivateKey.fromString("0000000000000000000000000000000000000000000000000000000000000002")));
        keys.put(BigInteger.valueOf(4), PrivateKey.derivePublicKey(PrivateKey.fromString("0000000000000000000000000000000000000000000000000000000000000004")));
        keys.put(BigInteger.valueOf(8), PrivateKey.derivePublicKey(PrivateKey.fromString("0000000000000000000000000000000000000000000000000000000000000008")));
        keys.put(BigInteger.valueOf(16), PrivateKey.derivePublicKey(PrivateKey.fromString("0000000000000000000000000000000000000000000000000000000000000010")));
        keys.put(BigInteger.valueOf(32), PrivateKey.derivePublicKey(PrivateKey.fromString("0000000000000000000000000000000000000000000000000000000000000020")));
        keys.put(BigInteger.valueOf(64), PrivateKey.derivePublicKey(PrivateKey.fromString("0000000000000000000000000000000000000000000000000000000000000040")));
        keys.put(BigInteger.valueOf(128), PrivateKey.derivePublicKey(PrivateKey.fromString("0000000000000000000000000000000000000000000000000000000000000080")));
        mint.addKeySet(KeySet.builder().id(VALID_KEYSET_ID).unit("sat").keys(keys).build());
        when(mintLoadService.load(any(UUID.class), Mockito.anyBoolean())).thenReturn(mint);

        MintTokensTask<Secret> task = new MintTokensTask<>(UUID.randomUUID(), postMintRequest, PaymentMethod.MOCK, mintLoadService, service, new DefaultSignatureVaultService());

        PostMintResponse response = task.execute();

        assertEquals(3, response.getBlindSignatures().size());

        // Verify the signatures are returned in descending order by amount
        assertEquals(64, response.getBlindSignatures().get(0).getAmount());
        assertEquals(32, response.getBlindSignatures().get(1).getAmount());
        assertEquals(4, response.getBlindSignatures().get(2).getAmount());
        assertEquals("004cf8cba2f93266", response.getBlindSignatures().get(0).getKeySetId().toString());
    }

    // Ensures mint quote can be created through the mocked gateway
    @Test
    public void mockMintQuote() throws CashuErrorException {
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

    // Ensures minting fails cleanly when the invoice is not paid
    @Test
    public void mockMintNotPaid() throws CashuErrorException {
        Secret secret = RandomStringSecret.fromString("3130c5cd3c69402549fc50df36873251edbeaf7efcec7c618cd8d2955202b518");
        byte[] r = Utils.hexStringToBytes("ea129258e052c096f08d394b40d93ba36e8074728677f0ce11efe1f3e06d2def");
        // Split 100 into proper denominations: 64 + 32 + 4 = 100
        // Each blinded message must have a unique public key so the signature vault stores them independently
        BlindedMessage blindedMessage1 = new BlindedMessage(64, KeysetId.fromString(VALID_KEYSET_ID), PublicKey.fromString("02d963e52f9d2f9519f8adedc8517389293d8028e0b33c4bc96b5e3cd128c27af2"), null);
        BlindedMessage blindedMessage2 = new BlindedMessage(32, KeysetId.fromString(VALID_KEYSET_ID), PublicKey.fromString("03a0434d9e47f3c86235477c7b1ae6ae5d3442d49b1943c2b752a68e2a47e247c7"), null);
        BlindedMessage blindedMessage3 = new BlindedMessage(4, KeysetId.fromString(VALID_KEYSET_ID), PublicKey.fromString("025f9d298d8d9e774c81ee64927a27e6e6b6e18f65447eb6a16808f92b84e44112"), null);

        String quoteId = "61f9b403-3464-489c-97c3-48ca468c099a";
        PostMintRequest postMintRequest = new PostMintRequest(quoteId, List.of(blindedMessage1, blindedMessage2, blindedMessage3), List.of(secret, secret), List.of(r));

        Gateway mockGateway = Mockito.mock(Gateway.class);
        when(mockGateway.getAmount(anyString())).thenReturn(100);
        when(mockGateway.checkPaymentStatus(anyString())).thenReturn(false);

        MintProtocolService service = Mockito.mock(MintProtocolService.class);
        Mockito.when(service.createGateway(PaymentMethod.MOCK)).thenReturn(mockGateway);
        Mockito.when(service.getPrivateKey(anyString(), anyInt(), any())).thenReturn(
                PrivateKey.fromString("a98675fc698aa718496e533de19d9d6bfb9c3bc9648e6ac9ad8416599881b3b5"));
        Mockito.when(service.getPrivateKeyForSigning(anyString(), anyInt(), any())).thenReturn(
                PrivateKey.fromString("a98675fc698aa718496e533de19d9d6bfb9c3bc9648e6ac9ad8416599881b3b5"));

        MintLoadService mintLoadService2 = Mockito.mock(MintLoadService.class);
        Mint mint = new Mint();
        // Create keys with standard Cashu denominations (powers of 2)
        Keys keys = new Keys();
        keys.put(BigInteger.valueOf(1), PrivateKey.derivePublicKey(PrivateKey.fromString("0000000000000000000000000000000000000000000000000000000000000001")));
        keys.put(BigInteger.valueOf(2), PrivateKey.derivePublicKey(PrivateKey.fromString("0000000000000000000000000000000000000000000000000000000000000002")));
        keys.put(BigInteger.valueOf(4), PrivateKey.derivePublicKey(PrivateKey.fromString("0000000000000000000000000000000000000000000000000000000000000004")));
        keys.put(BigInteger.valueOf(8), PrivateKey.derivePublicKey(PrivateKey.fromString("0000000000000000000000000000000000000000000000000000000000000008")));
        keys.put(BigInteger.valueOf(16), PrivateKey.derivePublicKey(PrivateKey.fromString("0000000000000000000000000000000000000000000000000000000000000010")));
        keys.put(BigInteger.valueOf(32), PrivateKey.derivePublicKey(PrivateKey.fromString("0000000000000000000000000000000000000000000000000000000000000020")));
        keys.put(BigInteger.valueOf(64), PrivateKey.derivePublicKey(PrivateKey.fromString("0000000000000000000000000000000000000000000000000000000000000040")));
        keys.put(BigInteger.valueOf(128), PrivateKey.derivePublicKey(PrivateKey.fromString("0000000000000000000000000000000000000000000000000000000000000080")));
        mint.addKeySet(KeySet.builder().id(VALID_KEYSET_ID).unit("sat").keys(keys).build());
        when(mintLoadService2.load(any(UUID.class), Mockito.anyBoolean())).thenReturn(mint);
        MintTokensTask<Secret> task = new MintTokensTask<>(UUID.randomUUID(), postMintRequest, PaymentMethod.MOCK, mintLoadService2, service, new DefaultSignatureVaultService());

        CashuErrorException exception = assertThrows(CashuErrorException.class, task::execute);
        CashuErrorCode errorCode = exception.getErrorCode();
        assertEquals("mint_invoice_not_paid_error", errorCode.name());
        assertEquals("Invoice not paid", errorCode.getDefaultDetail());
    }
}
