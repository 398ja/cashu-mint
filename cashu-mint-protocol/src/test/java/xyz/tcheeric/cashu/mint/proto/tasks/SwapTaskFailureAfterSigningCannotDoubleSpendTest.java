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
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.MintVaultService;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultService;
import xyz.tcheeric.cashu.mint.proto.service.SignatureVaultService;
import xyz.tcheeric.cashu.mint.proto.service.impl.DefaultSignatureVaultService;
import xyz.tcheeric.cashu.mint.proto.service.impl.MintProtocolServiceFactory;
import xyz.tcheeric.cashu.mint.proto.util.SignatureTestData;
import xyz.tcheeric.cashu.vault.db.model.MintEntity;
import xyz.tcheeric.cashu.vault.db.model.ProofEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * Issue #400: a swap that fails after signing must never leave redeemable outputs alongside
 * spendable inputs.
 *
 * <p>{@link SwapTaskRejectionLeavesNoSignatureTest} pins the guarantee for failures <em>before</em>
 * signing: nothing is signed, so nothing is redeemable. These tests cover the other side. Once
 * the outputs are signed and durable in the signature vault, the inputs must already be beyond
 * the wallet's reach, so no failure from that point on can let the same value be redeemed twice.
 *
 * <p>The swap achieves this by holding its inputs before it signs. "Spent" here therefore means
 * "no longer spendable by the wallet", which covers both the committed {@code SPENT} state and
 * the exclusive {@code PENDING} hold a stranded swap leaves behind for a reconciler.
 */
public class SwapTaskFailureAfterSigningCannotDoubleSpendTest {

    private static final String KEYSET_ID = "0123456789abcdef";
    private static final List<String> BLINDED_MESSAGES = List.of(
            "02d963e52f9d2f9519f8adedc8517389293d8028e0b33c4bc96b5e3cd128c27af2",
            "03a0434d9e47f3c86235477c7b1ae6ae5d3442d49b1943c2b752a68e2a47e247c7");

    /**
     * A swap whose input spend fails after the outputs are signed still leaves those inputs
     * bound and unspendable, so the value the outputs carry cannot also be spent as inputs.
     */
    @Test
    public void spendFailingAfterSigningLeavesTheInputsHeldNotSpendable() throws CashuErrorException {
        RecordingProofVault proofVault = new RecordingProofVault();
        proofVault.failCommit();
        PostSwapRequest<RandomStringSecret> request = balancedRequest(1);
        DefaultSignatureVaultService signatureVault = new DefaultSignatureVaultService();

        CashuErrorException failure = assertThrows(CashuErrorException.class,
                () -> executeSwap(request, proofVault, signatureVault));

        // The outputs really are redeemable through NUT-09 restore at this point, which is
        // precisely why the inputs must not be.
        for (BlindedMessage output : request.getBlindedMessages()) {
            assertNotNull(signatureVault.retrieve(output),
                    "the outputs were signed before the failure, so restore can hand them back");
        }
        assertEquals(CashuErrorCode.proofs_pending, failure.getErrorCode(),
                "a swap stranded after signing reports its inputs as still held");
        assertTrue(proofVault.everyInputIsHeldOrSpent(),
                "the inputs must not be spendable once the outputs are signed");
        assertTrue(proofVault.refundedHoldIds.isEmpty(),
                "releasing the inputs after signing would recreate the double-spend");
    }

    /**
     * A swap that cannot bind every input fails before signing, so no output is redeemable
     * through NUT-09 restore and the wallet keeps its money.
     */
    @Test
    public void inputsThatCannotAllBeHeldStopTheSwapBeforeAnythingIsSigned() throws CashuErrorException {
        RecordingProofVault proofVault = new RecordingProofVault();
        proofVault.bindOnlyTheFirstInput();
        PostSwapRequest<RandomStringSecret> request = balancedRequest(2);
        DefaultSignatureVaultService signatureVault = new DefaultSignatureVaultService();

        CashuErrorException failure = assertThrows(CashuErrorException.class,
                () -> executeSwap(request, proofVault, signatureVault));

        assertEquals(CashuErrorCode.proofs_not_bound, failure.getErrorCode(),
                "a swap that cannot hold all its inputs is refused");
        for (BlindedMessage output : request.getBlindedMessages()) {
            assertNull(signatureVault.retrieve(output),
                    "no output may be redeemable through NUT-09 restore");
        }
        assertEquals(List.of(proofVault.lastHoldId), proofVault.refundedHoldIds,
                "the partial hold is released so the wallet keeps its money");
    }

    /**
     * The mid-list partial failure the issue describes cannot arise: the inputs are claimed and
     * spent as a whole, so no swap ends with some inputs spent and others not.
     */
    @Test
    public void inputsAreHeldAndSpentAsAWholeNeverProofByProof() throws CashuErrorException {
        RecordingProofVault proofVault = new RecordingProofVault();
        PostSwapRequest<RandomStringSecret> request = balancedRequest(3);

        executeSwap(request, proofVault);

        assertEquals(1, proofVault.claimCalls,
                "all inputs are claimed in one call, so a claim cannot fail part-way down the list");
        assertEquals(1, proofVault.commitCalls,
                "all inputs are spent in one call, so a spend cannot fail part-way down the list");
        assertEquals(3, proofVault.spentSecrets.size(),
                "every input of a completed swap is spent");
    }

    /**
     * The inputs are held before any output is signed, not after, which is the ordering that
     * makes a post-signing failure survivable.
     */
    @Test
    public void inputsAreHeldBeforeTheFirstOutputIsSigned() throws CashuErrorException {
        RecordingProofVault proofVault = new RecordingProofVault();
        PostSwapRequest<RandomStringSecret> request = balancedRequest(1);

        executeSwap(request, proofVault);

        assertNotNull(proofVault.lastHoldId, "the swap took a hold on its inputs");
        assertTrue(proofVault.claimedBeforeAnySigning,
                "the hold is taken before the signing loop, never after it");
    }

    private void executeSwap(PostSwapRequest<RandomStringSecret> request,
                             ProofVaultService proofVault) throws CashuErrorException {
        executeSwap(request, proofVault, Mockito.mock(SignatureVaultService.class));
    }

    private void executeSwap(PostSwapRequest<RandomStringSecret> request,
                             ProofVaultService proofVault,
                             SignatureVaultService signatureVault) throws CashuErrorException {
        MintProtocolService service = Mockito.mock(MintProtocolService.class);
        Mockito.when(service.getPrivateKey(anyString(), anyInt(), any())).thenReturn(null);

        MintVaultService mintVault = Mockito.mock(MintVaultService.class);
        Mockito.when(mintVault.retrieveMint(anyString())).thenReturn(new MintEntity());

        try (MockedStatic<MintProtocolServiceFactory> factory =
                     Mockito.mockStatic(MintProtocolServiceFactory.class);
             MockedConstruction<VerifyProofsTask> verifyCons = Mockito.mockConstruction(
                     VerifyProofsTask.class, (mock, ctx) -> Mockito.doNothing().when(mock).execute());
             MockedConstruction<VerifyFeesTask> feesCons = Mockito.mockConstruction(
                     VerifyFeesTask.class, (mock, ctx) -> Mockito.doNothing().when(mock).execute());
             MockedConstruction<SignBlindedMessageTask> signCons = Mockito.mockConstruction(
                     SignBlindedMessageTask.class, (mock, ctx) -> {
                         BlindedMessage output = (BlindedMessage) ctx.arguments().get(1);
                         Mockito.doAnswer(invocation -> {
                             recordSigning(proofVault);
                             // The real task stores before it returns, and that durable write is
                             // what makes an output redeemable, so the stand-in must do it too.
                             BlindSignature signature = new BlindSignature(output.getAmount(),
                                     KeysetId.fromString(KEYSET_ID),
                                     SignatureTestData.sampleSignature(), null);
                             signatureVault.store(output, signature);
                             return signature;
                         }).when(mock).execute();
                     })) {

            factory.when(MintProtocolServiceFactory::getInstance).thenReturn(service);

            new SwapTask<>(UUID.randomUUID(), request, mintLoadService(), signatureVault,
                    mintVault, proofVault).execute();
        }
    }

    private void recordSigning(ProofVaultService proofVault) {
        if (proofVault instanceof RecordingProofVault recording) {
            recording.signingStarted();
        }
    }

    /**
     * A proof vault that records what the swap did to its inputs, so a test can ask whether the
     * inputs were left spendable rather than whether a particular method was called.
     */
    private static final class RecordingProofVault implements ProofVaultService {

        private final List<String> heldSecrets = new ArrayList<>();
        private final List<String> spentSecrets = new ArrayList<>();
        private final List<String> refundedHoldIds = new ArrayList<>();
        private String lastHoldId;
        private int claimCalls;
        private int commitCalls;
        private boolean signingHasStarted;
        private boolean claimedBeforeAnySigning;
        private boolean commitFails;
        private boolean bindOnlyFirst;

        void failCommit() {
            commitFails = true;
        }

        void bindOnlyTheFirstInput() {
            bindOnlyFirst = true;
        }

        void signingStarted() {
            signingHasStarted = true;
        }

        /** No input is spendable when each one is either spent outright or still exclusively held. */
        boolean everyInputIsHeldOrSpent() {
            return !heldSecrets.isEmpty()
                    && heldSecrets.stream().allMatch(secret ->
                            spentSecrets.contains(secret) || !refundedHoldIds.contains(lastHoldId));
        }

        @Override
        public int insertOrClaimForSaga(List<ProofEntity> proofs, String holdId, UUID mintId) {
            claimCalls++;
            claimedBeforeAnySigning = !signingHasStarted;
            lastHoldId = holdId;
            List<ProofEntity> bound = bindOnlyFirst ? proofs.subList(0, 1) : proofs;
            bound.forEach(proof -> heldSecrets.add(proof.getSecret()));
            return bound.size();
        }

        @Override
        public int commitSpentForSaga(String holdId) throws CashuErrorException {
            commitCalls++;
            if (commitFails) {
                throw new CashuErrorException(CashuErrorCode.internal_error, "vault unreachable");
            }
            spentSecrets.addAll(heldSecrets);
            return heldSecrets.size();
        }

        @Override
        public int refundForSaga(String holdId) {
            refundedHoldIds.add(holdId);
            return heldSecrets.size();
        }

        @Override
        public void store(ProofEntity proofEntity) {
            // The hold claims and settles inputs; the swap never stores them directly.
        }

        @Override
        public void invalidate(ProofEntity proofEntity) {
            // Superseded by commitSpentForSaga, which settles the whole hold at once.
        }

        @Override
        public void archive(ProofEntity proofEntity) {
            // Not reached by a swap.
        }

        @Override
        public void storePending(ProofEntity proofEntity) {
            // Superseded by insertOrClaimForSaga, which holds the whole input list at once.
        }

        @Override
        public ProofEntity retrieveProof(String secret) {
            return null;
        }

        @Override
        public ProofEntity retrieveProofByY(String yHex) {
            return null;
        }

        @Override
        public String storageKeyFor(String secret) {
            return secret;
        }
    }

    private PostSwapRequest<RandomStringSecret> balancedRequest(int inputCount) {
        List<Proof<RandomStringSecret>> inputs = new ArrayList<>(inputCount);
        for (int i = 0; i < inputCount; i++) {
            RSSProof proof = new RSSProof();
            proof.setAmount(8);
            proof.setKeySetId(KEYSET_ID);
            proof.setSecret(RandomStringSecret.create());
            proof.setUnblindedSignature(SignatureTestData.sampleSignature());
            inputs.add(proof);
        }

        BlindedMessage output = new BlindedMessage();
        output.setAmount(8 * inputCount);
        output.setKeySetId(KeysetId.fromString(KEYSET_ID));
        output.setBlindedMessage(PublicKey.fromString(BLINDED_MESSAGES.get(0)));

        PostSwapRequest<RandomStringSecret> request = new PostSwapRequest<>();
        request.setInputs(inputs);
        request.setBlindedMessages(List.of(output));
        return request;
    }

    private MintLoadService mintLoadService() throws CashuErrorException {
        MintLoadService mintLoadService = Mockito.mock(MintLoadService.class);
        Mockito.when(mintLoadService.load(any(UUID.class), Mockito.eq(false))).thenReturn(new Mint());
        KeySet keySet = KeySet.builder().id(KEYSET_ID).unit("sat").partPerThousand(0).build();
        Mockito.when(mintLoadService.keySet(KEYSET_ID)).thenReturn(keySet);
        Mockito.when(mintLoadService.keySets()).thenReturn(List.of(keySet));
        Mockito.when(mintLoadService.keySets(false)).thenReturn(List.of(keySet));
        Mockito.when(mintLoadService.keySets(true)).thenReturn(List.of());
        return mintLoadService;
    }
}
