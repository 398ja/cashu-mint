package xyz.tcheeric.cashu.mint.proto.tasks;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.common.BlindSignature;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.KeysetId;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.PublicKey;
import xyz.tcheeric.cashu.common.RSSProof;
import xyz.tcheeric.cashu.common.RandomStringSecret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.rest.ErrorResponse;
import xyz.tcheeric.cashu.entities.rest.PostSwapRequest;
import xyz.tcheeric.cashu.entities.rest.PostSwapResponse;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolServiceFactory;
import xyz.tcheeric.cashu.mint.proto.service.DefaultSignatureVaultService;
import xyz.tcheeric.cashu.mint.proto.util.SignatureTestData;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;

public class SwapTaskTest {

    private static final String VALID_KEYSET_ID = "0123456789abcdef";

    private RSSProof createProof() {
        RSSProof proof = new RSSProof();
        proof.setAmount(1);
        proof.setKeySetId(VALID_KEYSET_ID);
        proof.setSecret(RandomStringSecret.create());
        proof.setUnblindedSignature(SignatureTestData.sampleSignature());
        return proof;
    }

    private BlindedMessage createBlindedMessage() {
        BlindedMessage bm = new BlindedMessage();
        bm.setAmount(1);
        bm.setKeySetId(KeysetId.fromString(VALID_KEYSET_ID));
        bm.setBlindedMessage(PublicKey.fromString("02d963e52f9d2f9519f8adedc8517389293d8028e0b33c4bc96b5e3cd128c27af2"));
        return bm;
    }

    /**
     * Validates that a successful swap orchestrates proof verification, signing, and fee checks in order.
     */
    @Test
    public void execute() throws CashuErrorException {
        RSSProof proof = createProof();
        BlindedMessage bm = createBlindedMessage();

        PostSwapRequest<RandomStringSecret> request = new PostSwapRequest<>();
        request.setInputs(List.of(proof));
        request.setBlindedMessages(List.of(bm));

        Mint mint = new Mint();
        MintLoadService mintLoadService = Mockito.mock(MintLoadService.class);
        Mockito.when(mintLoadService.load(any(UUID.class), Mockito.eq(false))).thenReturn(mint);

        MintProtocolService service = Mockito.mock(MintProtocolService.class);
        Mockito.when(service.getPrivateKey(anyString(), anyInt(), any())).thenReturn(null);

        try (MockedStatic<MintProtocolServiceFactory> factory = Mockito.mockStatic(MintProtocolServiceFactory.class);
             MockedConstruction<VerifyProofsTask> verifyCons = Mockito.mockConstruction(VerifyProofsTask.class,
                     (mock, ctx) -> Mockito.doNothing().when(mock).execute());
             MockedConstruction<InvalidateProofsTask> invalidateCons = Mockito.mockConstruction(InvalidateProofsTask.class,
                     (mock, ctx) -> Mockito.when(mock.execute()).thenReturn(List.of(proof)));
             MockedConstruction<SignBlindedMessageTask> signCons = Mockito.mockConstruction(SignBlindedMessageTask.class,
                     (mock, ctx) -> Mockito.doReturn(new BlindSignature(1, KeysetId.fromString(VALID_KEYSET_ID), SignatureTestData.sampleSignature()))
                             .when(mock).execute());
             MockedConstruction<VerifyFeesTask> feesCons = Mockito.mockConstruction(VerifyFeesTask.class,
                     (mock, ctx) -> Mockito.doNothing().when(mock).execute())) {

            factory.when(MintProtocolServiceFactory::getInstance).thenReturn(service);

            SwapTask<RandomStringSecret> task = new SwapTask<>(UUID.randomUUID(), request, mintLoadService, new DefaultSignatureVaultService());
            PostSwapResponse response = task.execute();

            assertEquals(1, response.getBlindSignatures().size());
            Mockito.verify(verifyCons.constructed().get(0)).execute();
            Mockito.verify(invalidateCons.constructed().get(0)).execute();
            Mockito.verify(signCons.constructed().get(0)).execute();
            Mockito.verify(feesCons.constructed().get(0)).execute();
        }
    }

    /**
     * Ensures an informative error is returned when the mint cannot be loaded.
     */
    @Test
    public void executeMintNotFound() throws CashuErrorException {
        PostSwapRequest<RandomStringSecret> request = new PostSwapRequest<>();

        MintLoadService mintLoadService = Mockito.mock(MintLoadService.class);
        Mockito.when(mintLoadService.load(any(UUID.class), Mockito.eq(false))).thenReturn(null);

        SwapTask<RandomStringSecret> task = new SwapTask<>(UUID.randomUUID(), request, mintLoadService, new DefaultSignatureVaultService());

        CashuErrorException exception = assertThrows(CashuErrorException.class, task::execute);
        try {
            ErrorResponse error = new ObjectMapper().readValue(exception.getMessage(), ErrorResponse.class);
            assertEquals("swap_mint_not_found", error.code());
            assertEquals("Mint not found", error.message());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
