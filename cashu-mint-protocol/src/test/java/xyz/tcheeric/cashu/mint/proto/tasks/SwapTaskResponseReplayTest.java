package xyz.tcheeric.cashu.mint.proto.tasks;

import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.common.BlindSignature;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.KeySet;
import xyz.tcheeric.cashu.common.KeysetId;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.Proof;
import xyz.tcheeric.cashu.common.PublicKey;
import xyz.tcheeric.cashu.common.RSSProof;
import xyz.tcheeric.cashu.common.RandomStringSecret;
import xyz.tcheeric.cashu.common.nut00.CashuErrorCode;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.rest.nut03.PostSwapRequest;
import xyz.tcheeric.cashu.entities.rest.nut03.PostSwapResponse;
import xyz.tcheeric.cashu.mint.proto.domain.SignatureSource;
import xyz.tcheeric.cashu.mint.proto.ports.CachedSwapResponse;
import xyz.tcheeric.cashu.mint.proto.ports.SwapHoldRepository;
import xyz.tcheeric.cashu.mint.proto.ports.SwapResponseCache;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.MintVaultService;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultService;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultServiceMocks;
import xyz.tcheeric.cashu.mint.proto.service.SignatureVaultService;
import xyz.tcheeric.cashu.mint.proto.service.impl.DefaultSignatureVaultService;
import xyz.tcheeric.cashu.mint.proto.service.impl.MintProtocolServiceFactory;
import xyz.tcheeric.cashu.mint.proto.util.SignatureTestData;
import xyz.tcheeric.cashu.mint.proto.util.SwapRequestFingerprint;
import xyz.tcheeric.cashu.vault.db.model.MintEntity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * Issue #482: {@code POST /v1/swap} is a NUT-19 cached endpoint.
 *
 * <p>The swap runs against a real {@link DefaultSignatureVaultService}, which refuses a blinded
 * message it has already signed. That is what makes the replay tests honest: without the cache a
 * replay reaches the vault and is refused with {@code outputs_already_signed}, so a passing replay
 * can only have come from the cache.
 */
class SwapTaskResponseReplayTest {

    private static final String KEYSET_ID = "0123456789abcdef";
    private static final String B_ONE =
            "02d963e52f9d2f9519f8adedc8517389293d8028e0b33c4bc96b5e3cd128c27af2";
    private static final String B_TWO =
            "03a0434d9e47f3c86235477c7b1ae6ae5d3442d49b1943c2b752a68e2a47e247c7";

    private final InMemorySwapResponseCache cache = new InMemorySwapResponseCache();
    private final SignatureVaultService signatureVault = new DefaultSignatureVaultService();
    private final AtomicInteger signingCalls = new AtomicInteger();

    // Acceptance: an identical replay returns the same blind signatures instead of
    // outputs_already_signed, and nothing is signed a second time.
    @Test
    void anIdenticalReplayReturnsTheSameSignaturesWithoutSigningAgain() throws CashuErrorException {
        PostSwapRequest<RandomStringSecret> request = request(input("aa01"), outputs(B_ONE, B_TWO));

        PostSwapResponse original = executeSwap(request);
        int signedByTheOriginal = signingCalls.get();
        PostSwapResponse replay = executeSwap(request);

        assertThat(replay.getBlindSignatures())
                .as("the replay is answered with the signatures the original swap issued")
                .isEqualTo(original.getBlindSignatures())
                .hasSize(2);
        assertThat(signingCalls.get())
                .as("a replay must be answered from the cache, not signed again")
                .isEqualTo(signedByTheOriginal);
    }

    // Acceptance: a genuinely new swap is processed rather than served from the cache, even when
    // it asks for the same outputs as a swap already cached. Here that means it reaches the vault
    // and is refused there, rather than being handed the first swap's signatures.
    @Test
    void aNewSwapWithTheSameOutputsButDifferentInputsIsNotServedFromTheCache() throws CashuErrorException {
        executeSwap(request(input("aa01"), outputs(B_ONE, B_TWO)));
        int signedByTheFirst = signingCalls.get();

        PostSwapRequest<RandomStringSecret> differentInputs = request(input("bb02"), outputs(B_ONE, B_TWO));

        assertThatThrownBy(() -> executeSwap(differentInputs))
                .as("different inputs are a different swap; the cache must not answer it")
                .isInstanceOf(CashuErrorException.class)
                .extracting(e -> ((CashuErrorException) e).getErrorCode())
                .isEqualTo(CashuErrorCode.outputs_already_signed);
        assertThat(signingCalls.get())
                .as("the new swap was processed, reaching the signing step")
                .isGreaterThan(signedByTheFirst);
    }

    // A genuinely new swap with fresh outputs is signed and stored under its own key.
    @Test
    void aNewSwapIsProcessedAndCachedUnderItsOwnKey() throws CashuErrorException {
        PostSwapRequest<RandomStringSecret> first = request(input("aa01"), outputs(B_ONE));
        PostSwapRequest<RandomStringSecret> second = request(input("bb02"), outputs(B_TWO));

        executeSwap(first);
        PostSwapResponse secondResponse = executeSwap(second);

        assertThat(secondResponse.getBlindSignatures()).hasSize(1);
        assertThat(cache.entries).containsOnlyKeys(
                SwapRequestFingerprint.of(first).hex(), SwapRequestFingerprint.of(second).hex());
    }

    // A swap that fails stores nothing, so its replay is processed again rather than
    // answered with a response that was never sent.
    @Test
    void aFailedSwapIsNotCached() {
        PostSwapRequest<RandomStringSecret> request = request(input("aa01"), outputs(B_ONE));

        assertThatThrownBy(() -> executeSwap(request, failingSignature()))
                .isInstanceOf(CashuErrorException.class);
        assertThat(cache.entries).isEmpty();
    }

    // The cache is an aid, not a condition: a store that fails still returns the signatures the
    // swap issued, because refusing now would lose the wallet the outputs it paid for.
    @Test
    void aSwapWhoseResponseCannotBeCachedStillReturnsItsSignatures() throws CashuErrorException {
        cache.storeFailure = new IllegalStateException("database unavailable");

        PostSwapResponse response = executeSwap(request(input("aa01"), outputs(B_ONE)));

        assertThat(response.getBlindSignatures()).hasSize(1);
    }

    // A lookup that fails is a miss: a fresh swap still goes through.
    @Test
    void aFailedLookupIsTreatedAsAMiss() throws CashuErrorException {
        cache.lookupFailure = new IllegalStateException("database unavailable");

        PostSwapResponse response = executeSwap(request(input("aa01"), outputs(B_ONE)));

        assertThat(response.getBlindSignatures()).hasSize(1);
    }

    // A stored response that cannot be read back is reported as an internal error, not treated as
    // a miss: reprocessing a swap whose inputs are spent could only fail on them.
    @Test
    void anUnreadableCachedResponseIsAnInternalError() {
        PostSwapRequest<RandomStringSecret> request = request(input("aa01"), outputs(B_ONE));
        cache.store(SwapRequestFingerprint.of(request), "not json");

        assertThatThrownBy(() -> executeSwap(request))
                .isInstanceOf(CashuErrorException.class)
                .extracting(e -> ((CashuErrorException) e).getErrorCode())
                .isEqualTo(CashuErrorCode.internal_error);
        assertThat(signingCalls.get()).isZero();
    }

    // --- harness ---------------------------------------------------------------------------

    private static final class InMemorySwapResponseCache implements SwapResponseCache {

        private final Map<String, String> entries = new HashMap<>();
        private RuntimeException storeFailure;
        private RuntimeException lookupFailure;

        @Override
        public Optional<CachedSwapResponse> find(SwapRequestFingerprint fingerprint) {
            if (lookupFailure != null) {
                throw lookupFailure;
            }
            String json = entries.get(fingerprint.hex());
            return Optional.ofNullable(json).map(stored -> new StoredResponse(fingerprint.hex(), stored));
        }

        @Override
        public void store(SwapRequestFingerprint fingerprint, String responseJson) {
            if (storeFailure != null) {
                throw storeFailure;
            }
            entries.put(fingerprint.hex(), responseJson);
        }
    }

    private record StoredResponse(String requestFingerprint, String responseJson)
            implements CachedSwapResponse {

        @Override
        public Instant expiresAt() {
            return Instant.MAX;
        }
    }

    private interface SignatureFactory {
        BlindSignature sign(BlindedMessage output) throws CashuErrorException;
    }

    private static SignatureFactory failingSignature() {
        return output -> {
            throw new CashuErrorException(CashuErrorCode.internal_error);
        };
    }

    private SignatureFactory signingIntoTheVault() {
        return output -> {
            BlindSignature signature = new BlindSignature(output.getAmount(),
                    KeysetId.fromString(KEYSET_ID), SignatureTestData.sampleSignature(), null);
            signatureVault.store(output, signature, SignatureSource.SWAP);
            return signature;
        };
    }

    private PostSwapResponse executeSwap(PostSwapRequest<RandomStringSecret> request)
            throws CashuErrorException {
        return executeSwap(request, signingIntoTheVault());
    }

    private PostSwapResponse executeSwap(PostSwapRequest<RandomStringSecret> request,
                                         SignatureFactory signatures) throws CashuErrorException {
        MintVaultService mintVault = Mockito.mock(MintVaultService.class);
        Mockito.when(mintVault.retrieveMint(anyString())).thenReturn(new MintEntity());

        try (MockedStatic<MintProtocolServiceFactory> factory =
                     Mockito.mockStatic(MintProtocolServiceFactory.class);
             MockedConstruction<VerifyProofsTask> verifyCons = Mockito.mockConstruction(
                     VerifyProofsTask.class, (mock, ctx) -> Mockito.doNothing().when(mock).execute());
             MockedConstruction<VerifyFeesTask> feesCons = Mockito.mockConstruction(
                     VerifyFeesTask.class, (mock, ctx) -> Mockito.doNothing().when(mock).execute());
             MockedConstruction<ValidateTransactionTask> validateCons = Mockito.mockConstruction(
                     ValidateTransactionTask.class, (mock, ctx) -> Mockito.doNothing().when(mock).execute());
             MockedConstruction<SignBlindedMessageTask> signCons = Mockito.mockConstruction(
                     SignBlindedMessageTask.class, (mock, ctx) -> {
                         BlindedMessage output = (BlindedMessage) ctx.arguments().get(1);
                         Mockito.doAnswer(invocation -> {
                             signingCalls.incrementAndGet();
                             return signatures.sign(output);
                         }).when(mock).execute();
                     })) {

            factory.when(MintProtocolServiceFactory::getInstance)
                    .thenReturn(Mockito.mock(MintProtocolService.class));

            return new SwapTask<>(UUID.randomUUID(), request, mintLoadService(), signatureVault,
                    mintVault, proofVaultClaimingEverything(), new SwapHoldRepository() {
                    }, cache).execute();
        }
    }

    private static ProofVaultService proofVaultClaimingEverything() throws CashuErrorException {
        ProofVaultService proofVault = ProofVaultServiceMocks.keyingProofsByIssuanceKey();
        Mockito.when(proofVault.insertOrClaimForHold(any(), anyString(), any()))
                .thenAnswer(invocation -> ((List<?>) invocation.getArgument(0)).size());
        Mockito.when(proofVault.commitSpentForHold(anyString())).thenReturn(1);
        return proofVault;
    }

    private static MintLoadService mintLoadService() throws CashuErrorException {
        Mint mint = new Mint(UUID.randomUUID().toString());
        mint.addKeySet(KeySet.builder().id(KEYSET_ID).unit("sat").build());
        MintLoadService loadService = Mockito.mock(MintLoadService.class);
        Mockito.when(loadService.load(any(UUID.class), Mockito.eq(false))).thenReturn(mint);
        return loadService;
    }

    private static Proof<RandomStringSecret> input(String secret) {
        RSSProof proof = new RSSProof();
        proof.setAmount(16);
        proof.setKeySetId(KEYSET_ID);
        proof.setSecret(RandomStringSecret.fromString(secret));
        proof.setUnblindedSignature(SignatureTestData.sampleSignature());
        return proof;
    }

    private static List<BlindedMessage> outputs(String... blindedMessages) {
        List<BlindedMessage> outputs = new ArrayList<>();
        for (String blindedMessage : blindedMessages) {
            BlindedMessage output = new BlindedMessage();
            output.setAmount(8);
            output.setKeySetId(KeysetId.fromString(KEYSET_ID));
            output.setBlindedMessage(PublicKey.fromString(blindedMessage));
            outputs.add(output);
        }
        return outputs;
    }

    private static PostSwapRequest<RandomStringSecret> request(Proof<RandomStringSecret> input,
                                                               List<BlindedMessage> outputs) {
        PostSwapRequest<RandomStringSecret> request = new PostSwapRequest<>();
        request.setInputs(List.of(input));
        request.setBlindedMessages(outputs);
        return request;
    }
}
