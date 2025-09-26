package xyz.tcheeric.cashu.mint.proto.integration;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.common.*;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.crypto.util.Utils;
import xyz.tcheeric.cashu.entities.rest.PostMintRequest;
import xyz.tcheeric.cashu.entities.rest.PostMintResponse;
import xyz.tcheeric.cashu.entities.rest.PostRestoreRequest;
import xyz.tcheeric.cashu.entities.rest.PostRestoreResponse;
import xyz.tcheeric.cashu.mint.proto.nut.NUT04;
import xyz.tcheeric.cashu.mint.proto.nut.NUT09;
import xyz.tcheeric.cashu.mint.proto.service.impl.DefaultSignatureVaultService;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.SignatureVaultService;
import xyz.tcheeric.gateway.common.Gateway;

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
        BlindedMessage blindedMessage = new BlindedMessage(100, KeysetId.fromString(VALID_KEYSET_ID),
                PublicKey.fromString("02d963e52f9d2f9519f8adedc8517389293d8028e0b33c4bc96b5e3cd128c27af2"), null);
        String quoteId = "61f9b403-3464-489c-97c3-48ca468c099a";
        PostMintRequest<Secret> mintRequest = new PostMintRequest<>(quoteId, List.of(blindedMessage),
                List.of(secret, secret), List.of(r));

        Gateway gateway = Mockito.mock(Gateway.class);
        Mockito.when(gateway.getAmount(Mockito.anyString())).thenReturn(100);
        Mockito.when(gateway.checkPaymentStatus(Mockito.anyString())).thenReturn(true);

        MintProtocolService protocolService = Mockito.mock(MintProtocolService.class);
        Mockito.when(protocolService.createGateway(PaymentMethod.MOCK)).thenReturn(gateway);
        Mockito.when(protocolService.getPrivateKey(Mockito.anyString(), Mockito.anyInt(), Mockito.any()))
                .thenReturn(PrivateKey.fromString("a98675fc698aa718496e533de19d9d6bfb9c3bc9648e6ac9ad8416599881b3b5"));

        MintLoadService mintLoadService = Mockito.mock(MintLoadService.class);
        Mockito.when(mintLoadService.load(Mockito.any(UUID.class), Mockito.eq(false))).thenReturn(new Mint());

        SignatureVaultService vault = new DefaultSignatureVaultService();

        // Act: mint then restore
        PostMintResponse mintResponse = NUT04.mint(UUID.randomUUID(), mintRequest, PaymentMethod.MOCK, null,
                mintLoadService, protocolService, vault);

        PostRestoreRequest restoreRequest = new PostRestoreRequest(List.of(blindedMessage));
        PostRestoreResponse restoreResponse = NUT09.restore(restoreRequest, vault);

        // Assert
        assertEquals(mintResponse.getBlindSignatures(), restoreResponse.getBlindSignatures());
        assertEquals(List.of(blindedMessage), restoreResponse.getBlindedMessages());
    }
}
