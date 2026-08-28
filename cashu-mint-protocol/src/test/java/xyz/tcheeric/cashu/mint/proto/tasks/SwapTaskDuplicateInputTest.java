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
import xyz.tcheeric.cashu.entities.rest.nut03.PostSwapRequest;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.impl.DefaultSignatureVaultService;
import xyz.tcheeric.cashu.mint.proto.service.impl.MintProtocolServiceFactory;
import xyz.tcheeric.cashu.mint.proto.util.ProofLockManager;
import xyz.tcheeric.cashu.mint.proto.util.SignatureTestData;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * NUT-03 duplicate-input rejection (error code {@code 11007}), audit finding M5.
 *
 * <p>The audit asked for the duplicate-input case to be written as a test before
 * assuming anything about exploitability, because the answer depends on layers
 * below {@code SwapTask}. These tests record what those layers actually do.
 */
public class SwapTaskDuplicateInputTest {

    private static final String VALID_KEYSET_ID = "0123456789abcdef";

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
        bm.setBlindedMessage(PublicKey.fromString(
                "02d963e52f9d2f9519f8adedc8517389293d8028e0b33c4bc96b5e3cd128c27af2"));
        return bm;
    }

    /**
     * The proof lock manager deduplicates secrets, so presenting the same proof twice
     * acquires its lock only once and does not deadlock. This is why a duplicated input
     * cannot be stopped by the locking layer and needs an explicit check.
     */
    @Test
    public void lockingTheSameSecretTwiceDoesNotBlock() {
        String secret = "the-same-secret";
        AtomicBoolean completed = new AtomicBoolean(false);

        try (ProofLockManager.ProofLock ignored =
                     ProofLockManager.lockSecrets(List.of(secret, secret))) {
            completed.set(true);
        }

        assertTrue(completed.get(),
                "locking a duplicated secret must not deadlock; the manager dedupes, "
                        + "so it cannot be relied on to reject duplicate inputs");
    }

    /**
     * A wallet that presents one proof twice must be rejected with the duplicate-inputs
     * code rather than having its amount counted twice, which would let a single 1000-sat
     * proof claim 2000 sats of outputs.
     */
    @Test
    public void duplicatedInputProofIsRejected() throws CashuErrorException {
        RSSProof proof = createProof(1000);
        BlindedMessage bm = createBlindedMessage(2000);

        PostSwapRequest<RandomStringSecret> request = new PostSwapRequest<>();
        request.setInputs(List.of(proof, proof));
        request.setBlindedMessages(List.of(bm));

        Mint mint = new Mint();
        MintLoadService mintLoadService = Mockito.mock(MintLoadService.class);
        Mockito.when(mintLoadService.load(any(UUID.class), Mockito.eq(false))).thenReturn(mint);

        MintProtocolService service = Mockito.mock(MintProtocolService.class);
        Mockito.when(service.getPrivateKey(anyString(), anyInt(), any())).thenReturn(null);

        try (MockedStatic<MintProtocolServiceFactory> factory =
                     Mockito.mockStatic(MintProtocolServiceFactory.class);
             MockedConstruction<VerifyProofsTask> verifyCons = Mockito.mockConstruction(
                     VerifyProofsTask.class, (mock, ctx) -> Mockito.doNothing().when(mock).execute());
             MockedConstruction<SignBlindedMessageTask> signCons = Mockito.mockConstruction(
                     SignBlindedMessageTask.class,
                     (mock, ctx) -> Mockito.doReturn(new BlindSignature(
                             2000,
                             KeysetId.fromString(VALID_KEYSET_ID),
                             SignatureTestData.sampleSignature(),
                             null)).when(mock).execute());
             MockedConstruction<VerifyFeesTask> feesCons = Mockito.mockConstruction(
                     VerifyFeesTask.class, (mock, ctx) -> Mockito.doNothing().when(mock).execute());
             MockedConstruction<InvalidateProofsTask> invalidateCons = Mockito.mockConstruction(
                     InvalidateProofsTask.class,
                     (mock, ctx) -> Mockito.when(mock.execute()).thenReturn(List.of(proof)))) {

            factory.when(MintProtocolServiceFactory::getInstance).thenReturn(service);

            SwapTask<RandomStringSecret> task = new SwapTask<>(
                    UUID.randomUUID(), request, mintLoadService, new DefaultSignatureVaultService());

            CashuErrorException exception = assertThrows(CashuErrorException.class, task::execute);
            assertEquals("11007", errorCodeOf(exception));
            assertTrue(signCons.constructed().isEmpty(),
                    "a duplicated input must be refused before anything is signed");
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
