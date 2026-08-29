package xyz.tcheeric.cashu.mint.proto.tasks;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.KeySet;
import xyz.tcheeric.cashu.common.KeysetId;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.PublicKey;
import xyz.tcheeric.cashu.common.RSSProof;
import xyz.tcheeric.cashu.common.RandomStringSecret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.rest.nut03.PostSwapRequest;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.MintVaultService;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultService;
import xyz.tcheeric.cashu.mint.proto.service.SignatureVaultService;
import xyz.tcheeric.cashu.mint.proto.service.impl.MintProtocolServiceFactory;
import xyz.tcheeric.cashu.mint.proto.util.SignatureTestData;
import xyz.tcheeric.cashu.vault.db.model.MintEntity;
import xyz.tcheeric.cashu.vault.db.model.ProofEntity;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * The swap must spend its inputs through the vault services the caller supplied.
 *
 * <p>The swap previously let {@link InvalidateProofsTask} construct its own vault client. That
 * bound the spend to whatever vault the default client points at rather than the one wired into
 * the running mint, so a mint whose vault is configured anywhere else failed the swap after it
 * had already signed and stored the outputs. Interoperability issue #397.
 */
public class SwapTaskInvalidatesAgainstTheInjectedVaultTest {

    private static final String KEYSET_ID = "0123456789abcdef";
    private static final String BLINDED_MESSAGE =
            "02d963e52f9d2f9519f8adedc8517389293d8028e0b33c4bc96b5e3cd128c27af2";

    /**
     * A swap invalidates its inputs through the injected vault services, never through a vault
     * client the task builds for itself.
     */
    @Test
    public void swapSpendsItsInputsThroughTheInjectedVault() throws CashuErrorException {
        PostSwapRequest<RandomStringSecret> request = balancedRequest();
        MintLoadService mintLoadService = mintLoadServiceReturningKeyset();

        MintVaultService mintVaultService = Mockito.mock(MintVaultService.class);
        Mockito.when(mintVaultService.retrieveMint(anyString())).thenReturn(new MintEntity());
        ProofVaultService proofVaultService = Mockito.mock(ProofVaultService.class);
        Mockito.when(proofVaultService.storageKeyFor(anyString())).thenReturn("stored-key");
        SignatureVaultService signatureVaultService = Mockito.mock(SignatureVaultService.class);

        MintProtocolService protocolService = Mockito.mock(MintProtocolService.class);
        Mockito.when(protocolService.getPrivateKeyForSigning(anyString(), anyInt(), any()))
                .thenReturn(null);

        try (MockedStatic<MintProtocolServiceFactory> factory =
                     Mockito.mockStatic(MintProtocolServiceFactory.class)) {
            factory.when(MintProtocolServiceFactory::getInstance).thenReturn(protocolService);

            new InvalidateProofsTask<>(new Mint(), request.getInputs(),
                    mintVaultService, proofVaultService).execute();
        }

        Mockito.verify(proofVaultService).store(any(ProofEntity.class));
        Mockito.verify(proofVaultService).invalidate(any(ProofEntity.class));
        Mockito.verifyNoInteractions(signatureVaultService);
    }

    private PostSwapRequest<RandomStringSecret> balancedRequest() {
        RSSProof proof = new RSSProof();
        proof.setAmount(8);
        proof.setKeySetId(KEYSET_ID);
        proof.setSecret(RandomStringSecret.create());
        proof.setUnblindedSignature(SignatureTestData.sampleSignature());

        BlindedMessage output = new BlindedMessage();
        output.setAmount(8);
        output.setKeySetId(KeysetId.fromString(KEYSET_ID));
        output.setBlindedMessage(PublicKey.fromString(BLINDED_MESSAGE));

        PostSwapRequest<RandomStringSecret> request = new PostSwapRequest<>();
        request.setInputs(List.of(proof));
        request.setBlindedMessages(List.of(output));
        return request;
    }

    private MintLoadService mintLoadServiceReturningKeyset() throws CashuErrorException {
        MintLoadService mintLoadService = Mockito.mock(MintLoadService.class);
        Mockito.when(mintLoadService.load(any(UUID.class), Mockito.eq(false))).thenReturn(new Mint());
        KeySet keySet = KeySet.builder().id(KEYSET_ID).unit("sat").partPerThousand(0).build();
        Mockito.when(mintLoadService.keySet(KEYSET_ID)).thenReturn(keySet);
        Mockito.when(mintLoadService.keySets()).thenReturn(List.of(keySet));
        return mintLoadService;
    }
}
