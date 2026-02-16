package xyz.tcheeric.cashu.mint.proto.integration;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.common.*;
import xyz.tcheeric.cashu.common.nut18.PaymentMethod;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.crypto.util.Utils;
import xyz.tcheeric.cashu.entities.rest.nut04.PostMintRequest;
import xyz.tcheeric.cashu.entities.rest.nut04.PostMintResponse;
import xyz.tcheeric.cashu.entities.rest.nut09.PostRestoreRequest;
import xyz.tcheeric.cashu.entities.rest.nut09.PostRestoreResponse;
import xyz.tcheeric.cashu.mint.proto.nut.NUT04;
import xyz.tcheeric.cashu.mint.proto.nut.NUT09;
import xyz.tcheeric.cashu.mint.proto.service.impl.DefaultSignatureVaultService;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.SignatureVaultService;
import xyz.tcheeric.payment.adapter.core.common.Gateway;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class MintThenRestoreIntegrationTest {

    private static final String VALID_KEYSET_ID = "004cf8cba2f93266";

    /**
     * Mints a signature and verifies it can be restored in a later request using the shared vault.
     */
    @Test
    void mintThenRestore() throws CashuErrorException {
        // Arrange mint request
        Secret secret = RandomStringSecret.fromString("3130c5cd3c69402549fc50df36873251edbeaf7efcec7c618cd8d2955202b518");
        byte[] r = Utils.hexStringToBytes("ea129258e052c096f08d394b40d93ba36e8074728677f0ce11efe1f3e06d2def");
        // Split 100 into proper denominations: 64 + 32 + 4 = 100
        // Each blinded message must have a unique public key so the vault stores them independently
        BlindedMessage blindedMessage1 = new BlindedMessage(64, KeysetId.fromString(VALID_KEYSET_ID),
                PublicKey.fromString("02d963e52f9d2f9519f8adedc8517389293d8028e0b33c4bc96b5e3cd128c27af2"), null);
        BlindedMessage blindedMessage2 = new BlindedMessage(32, KeysetId.fromString(VALID_KEYSET_ID),
                PublicKey.fromString("03a0434d9e47f3c86235477c7b1ae6ae5d3442d49b1943c2b752a68e2a47e247c7"), null);
        BlindedMessage blindedMessage3 = new BlindedMessage(4, KeysetId.fromString(VALID_KEYSET_ID),
                PublicKey.fromString("025f9d298d8d9e774c81ee64927a27e6e6b6e18f65447eb6a16808f92b84e44112"), null);
        String quoteId = "61f9b403-3464-489c-97c3-48ca468c099a";
        PostMintRequest<Secret> mintRequest = new PostMintRequest<>(quoteId, List.of(blindedMessage1, blindedMessage2, blindedMessage3),
                List.of(secret, secret), List.of(r));

        Gateway gateway = Mockito.mock(Gateway.class);
        Mockito.when(gateway.getAmount(Mockito.anyString())).thenReturn(100);
        Mockito.when(gateway.checkPaymentStatus(Mockito.anyString())).thenReturn(true);

        MintProtocolService protocolService = Mockito.mock(MintProtocolService.class);
        Mockito.when(protocolService.createGateway(PaymentMethod.MOCK)).thenReturn(gateway);
        Mockito.when(protocolService.getPrivateKey(Mockito.anyString(), Mockito.anyInt(), Mockito.any()))
                .thenReturn(PrivateKey.fromString("a98675fc698aa718496e533de19d9d6bfb9c3bc9648e6ac9ad8416599881b3b5"));

        MintLoadService mintLoadService = Mockito.mock(MintLoadService.class);
        Mint mint = new Mint();
        // Create keys with standard Cashu denominations (powers of 2)
        Keys keys = new Keys();
        keys.put(java.math.BigInteger.valueOf(1), PrivateKey.derivePublicKey(PrivateKey.fromString("0000000000000000000000000000000000000000000000000000000000000001")));
        keys.put(java.math.BigInteger.valueOf(2), PrivateKey.derivePublicKey(PrivateKey.fromString("0000000000000000000000000000000000000000000000000000000000000002")));
        keys.put(java.math.BigInteger.valueOf(4), PrivateKey.derivePublicKey(PrivateKey.fromString("0000000000000000000000000000000000000000000000000000000000000004")));
        keys.put(java.math.BigInteger.valueOf(8), PrivateKey.derivePublicKey(PrivateKey.fromString("0000000000000000000000000000000000000000000000000000000000000008")));
        keys.put(java.math.BigInteger.valueOf(16), PrivateKey.derivePublicKey(PrivateKey.fromString("0000000000000000000000000000000000000000000000000000000000000010")));
        keys.put(java.math.BigInteger.valueOf(32), PrivateKey.derivePublicKey(PrivateKey.fromString("0000000000000000000000000000000000000000000000000000000000000020")));
        keys.put(java.math.BigInteger.valueOf(64), PrivateKey.derivePublicKey(PrivateKey.fromString("0000000000000000000000000000000000000000000000000000000000000040")));
        keys.put(java.math.BigInteger.valueOf(128), PrivateKey.derivePublicKey(PrivateKey.fromString("0000000000000000000000000000000000000000000000000000000000000080")));
        mint.addKeySet(KeySet.builder().id(VALID_KEYSET_ID).unit("sat").keys(keys).build());
        Mockito.when(mintLoadService.load(Mockito.any(UUID.class), Mockito.eq(false))).thenReturn(mint);

        SignatureVaultService vault = new DefaultSignatureVaultService();

        // Act: mint then restore
        PostMintResponse mintResponse = NUT04.mint(UUID.randomUUID(), mintRequest, PaymentMethod.MOCK, null,
                mintLoadService, protocolService, vault);

        PostRestoreRequest restoreRequest = new PostRestoreRequest(List.of(blindedMessage1, blindedMessage2, blindedMessage3));
        PostRestoreResponse restoreResponse = NUT09.restore(restoreRequest, vault);

        // Assert
        assertEquals(mintResponse.getBlindSignatures(), restoreResponse.getBlindSignatures());
        assertEquals(List.of(blindedMessage1, blindedMessage2, blindedMessage3), restoreResponse.getBlindedMessages());
    }
}
