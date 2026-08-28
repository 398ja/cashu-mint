package xyz.tcheeric.cashu.mint.proto.tasks;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
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
import xyz.tcheeric.cashu.mint.proto.error.ErrorResponse;
import xyz.tcheeric.cashu.entities.rest.nut03.PostSwapRequest;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.impl.DefaultSignatureVaultService;
import xyz.tcheeric.cashu.mint.proto.service.impl.MintProtocolServiceFactory;
import xyz.tcheeric.cashu.mint.proto.util.SignatureTestData;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * NUT-02 balance equation ordering, audit finding M2.
 *
 * <p>Validation runs before signing, so a swap the mint rejects leaves nothing behind in the
 * signature vault for NUT-09 restore to hand back.
 */
public class SwapTaskRejectionLeavesNoSignatureTest {

    private static final String VALID_KEYSET_ID = "0123456789abcdef";
    private static final String BLINDED_MESSAGE =
            "02d963e52f9d2f9519f8adedc8517389293d8028e0b33c4bc96b5e3cd128c27af2";

    private RSSProof createProof(int amount) {
        RSSProof proof = new RSSProof();
        proof.setAmount(amount);
        proof.setKeySetId(VALID_KEYSET_ID);
        proof.setSecret(RandomStringSecret.create());
        proof.setUnblindedSignature(SignatureTestData.sampleSignature());
        return proof;
    }

    private BlindedMessage createBlindedMessage(int amount) {
        BlindedMessage bm = new BlindedMessage();
        bm.setAmount(amount);
        bm.setKeySetId(KeysetId.fromString(VALID_KEYSET_ID));
        bm.setBlindedMessage(PublicKey.fromString(BLINDED_MESSAGE));
        return bm;
    }

    /**
     * An unbalanced swap is refused before anything is signed, so no blind signature is created
     * and the vault has nothing to return to a later NUT-09 restore call.
     */
    @Test
    public void unbalancedSwapLeavesTheSignatureVaultUntouched() throws CashuErrorException {
        RSSProof proof = createProof(1000);
        BlindedMessage output = createBlindedMessage(2000);

        PostSwapRequest<RandomStringSecret> request = new PostSwapRequest<>();
        request.setInputs(List.of(proof));
        request.setBlindedMessages(List.of(output));

        Mint mint = new Mint();
        MintLoadService mintLoadService = Mockito.mock(MintLoadService.class);
        Mockito.when(mintLoadService.load(any(UUID.class), Mockito.eq(false))).thenReturn(mint);
        KeySet keySet = KeySet.builder().id(VALID_KEYSET_ID).unit("sat").partPerThousand(0).build();
        Mockito.when(mintLoadService.keySet(VALID_KEYSET_ID)).thenReturn(keySet);
        Mockito.when(mintLoadService.keySets()).thenReturn(List.of(keySet));
        Mockito.when(mintLoadService.keySets(false)).thenReturn(List.of(keySet));
        Mockito.when(mintLoadService.keySets(true)).thenReturn(List.of());

        MintProtocolService service = Mockito.mock(MintProtocolService.class);
        Mockito.when(service.getPrivateKey(anyString(), anyInt(), any())).thenReturn(null);

        DefaultSignatureVaultService signatureVault = new DefaultSignatureVaultService();

        try (MockedStatic<MintProtocolServiceFactory> factory =
                     Mockito.mockStatic(MintProtocolServiceFactory.class);
             MockedConstruction<VerifyProofsTask> verifyCons = Mockito.mockConstruction(
                     VerifyProofsTask.class, (mock, ctx) -> Mockito.doNothing().when(mock).execute());
             MockedConstruction<SignBlindedMessageTask> signCons =
                     Mockito.mockConstruction(SignBlindedMessageTask.class);
             MockedConstruction<InvalidateProofsTask> invalidateCons =
                     Mockito.mockConstruction(InvalidateProofsTask.class)) {

            factory.when(MintProtocolServiceFactory::getInstance).thenReturn(service);

            SwapTask<RandomStringSecret> task = new SwapTask<>(
                    UUID.randomUUID(), request, mintLoadService, signatureVault);

            CashuErrorException exception = assertThrows(CashuErrorException.class, task::execute);

            assertEquals("transaction_not_balanced", errorCodeOf(exception),
                    "an unbalanced swap raises the error_codes.md 11005 condition");
            assertTrue(signCons.constructed().isEmpty(),
                    "validation must run before the signing loop");
            assertNull(signatureVault.retrieve(output),
                    "a rejected swap must leave no signature retrievable through NUT-09 restore");
            assertTrue(invalidateCons.constructed().isEmpty(),
                    "a rejected swap must not spend its inputs");
        }
    }

    private static String errorCodeOf(CashuErrorException exception) {
        try {
            return new ObjectMapper().readValue(exception.getMessage(), ErrorResponse.class).code();
        } catch (Exception e) {
            throw new IllegalStateException("error payload was not the expected JSON", e);
        }
    }
}
