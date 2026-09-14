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
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.rest.nut03.PostSwapRequest;
import xyz.tcheeric.cashu.mint.proto.domain.SwapHoldPhase;
import xyz.tcheeric.cashu.mint.proto.ports.SwapHoldRepository;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.MintVaultService;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultService;
import xyz.tcheeric.cashu.mint.proto.service.SignatureVaultService;
import xyz.tcheeric.cashu.mint.proto.service.impl.MintProtocolServiceFactory;
import xyz.tcheeric.cashu.mint.proto.util.SignatureTestData;
import xyz.tcheeric.cashu.vault.db.model.MintEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * The swap hold must be recorded as {@code SIGNING} <em>before</em> the first signature
 * (AppSec follow-up, issue #437).
 *
 * <h2>Why this test exists</h2>
 *
 * <p>{@code SwapProofHold.markSigning()} carries a careful explanation of why the write happens
 * before signing rather than after:
 *
 * <blockquote>a crash either side of this write must be read the same way: signing may have
 * begun, so the hold can only be committed. Recording it afterwards would leave the dangerous
 * case indistinguishable from an untouched hold, and releasing that hold is the double-spend
 * this class prevents.</blockquote>
 *
 * <p>The reasoning is correct, and until now nothing asserted it. Swapping the two lines in
 * {@code SwapTask.signAgainstHeldInputs} left all 379 tests in this module green, and no test
 * in the module so much as referenced {@code markSigning}. A refactor that tidied those two
 * statements into a more natural-looking order would have passed CI and reopened a
 * double-spend window.
 *
 * <p>That is the general point behind #437: a property defended only by a comment is defended
 * only until the next person disagrees with the comment.
 */
public class SwapProofHoldSigningOrderTest {

    private static final String KEYSET_ID = "0123456789abcdef";
    private static final List<String> BLINDED_MESSAGES = List.of(
            "02d963e52f9d2f9519f8adedc8517389293d8028e0b33c4bc96b5e3cd128c27af2",
            "03a0434d9e47f3c86235477c7b1ae6ae5d3442d49b1943c2b752a68e2a47e247c7");

    /**
     * The hold reaches {@code SIGNING} before any output is signed.
     *
     * <p>Asserted on the order of two observable events rather than on a method call, so the test
     * describes the guarantee — "the durable record says signing may have begun before it could
     * have begun" — rather than the current implementation of it.
     */
    @Test
    public void theHoldIsMarkedSigningBeforeTheFirstSignature() throws CashuErrorException {
        OrderRecordingHoldRepository holds = new OrderRecordingHoldRepository();

        executeSwap(balancedRequest(1), holds);

        assertThat(holds.markedSigningAt)
                .as("the swap recorded SIGNING on its hold")
                .isNotNegative();
        assertThat(holds.firstSignatureAt)
                .as("the swap signed at least one output")
                .isNotNegative();
        assertThat(holds.markedSigningAt)
                .as("SIGNING must be durable before the first signature exists: a crash between "
                        + "them has to be read as 'signing may have begun', or the hold gets "
                        + "released and the same value is spendable twice")
                .isLessThan(holds.firstSignatureAt);
    }

    /** The hold is opened before it is advanced, so the phase is never written to nothing. */
    @Test
    public void theHoldIsOpenedBeforeItIsMarkedSigning() throws CashuErrorException {
        OrderRecordingHoldRepository holds = new OrderRecordingHoldRepository();

        executeSwap(balancedRequest(1), holds);

        assertThat(holds.openedAt).as("the swap opened a hold record").isNotNegative();
        assertThat(holds.openedAt).isLessThan(holds.markedSigningAt);
    }

    /**
     * A completed swap ends {@code COMMITTED}, never {@code RELEASED}.
     *
     * <p>Releasing a hold whose outputs are signed is the double-spend {@code SwapProofHold}
     * exists to prevent, so the terminal phase is worth pinning too.
     */
    @Test
    public void aCompletedSwapCommitsItsHoldRatherThanReleasingIt() throws CashuErrorException {
        OrderRecordingHoldRepository holds = new OrderRecordingHoldRepository();

        executeSwap(balancedRequest(1), holds);

        assertThat(holds.phases)
                .as("a swap that signed its outputs must commit the hold, never release it")
                .contains(SwapHoldPhase.COMMITTED)
                .doesNotContain(SwapHoldPhase.RELEASED);
    }

    // --- harness ---------------------------------------------------------------------------

    /** Records when each hold event happened, on one monotonic counter shared with signing. */
    private static final class OrderRecordingHoldRepository implements SwapHoldRepository {

        private int clock = 0;
        private int openedAt = -1;
        private int markedSigningAt = -1;
        private int firstSignatureAt = -1;
        private final List<SwapHoldPhase> phases = new ArrayList<>();

        @Override
        public void open(String holdId, int inputCount) {
            openedAt = clock++;
        }

        @Override
        public void advance(String holdId, SwapHoldPhase phase) {
            phases.add(phase);
            if (phase == SwapHoldPhase.SIGNING) {
                markedSigningAt = clock++;
            } else {
                clock++;
            }
        }

        void recordSignature() {
            if (firstSignatureAt < 0) {
                firstSignatureAt = clock++;
            }
        }
    }

    private void executeSwap(PostSwapRequest<RandomStringSecret> request,
                             OrderRecordingHoldRepository holds) throws CashuErrorException {
        MintProtocolService service = Mockito.mock(MintProtocolService.class);
        Mockito.when(service.getPrivateKey(anyString(), anyInt(), any())).thenReturn(null);

        MintVaultService mintVault = Mockito.mock(MintVaultService.class);
        Mockito.when(mintVault.retrieveMint(anyString())).thenReturn(new MintEntity());

        ProofVaultService proofVault = Mockito.mock(ProofVaultService.class);
        Mockito.when(proofVault.insertOrClaimForHold(any(), anyString(), any()))
                .thenAnswer(invocation -> ((List<?>) invocation.getArgument(0)).size());
        Mockito.when(proofVault.commitSpentForHold(anyString()))
                .thenReturn(request.getInputs().size());

        SignatureVaultService signatureVault = Mockito.mock(SignatureVaultService.class);

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
                             holds.recordSignature();
                             return new BlindSignature(output.getAmount(),
                                     KeysetId.fromString(KEYSET_ID),
                                     SignatureTestData.sampleSignature(), null);
                         }).when(mock).execute();
                     })) {

            factory.when(MintProtocolServiceFactory::getInstance).thenReturn(service);

            new SwapTask<>(UUID.randomUUID(), request, mintLoadService(), signatureVault,
                    mintVault, proofVault, holds).execute();
        }
    }

    private MintLoadService mintLoadService() throws CashuErrorException {
        Mint mint = new Mint(UUID.randomUUID().toString());
        mint.addKeySet(KeySet.builder().id(KEYSET_ID).unit("sat").build());
        MintLoadService loadService = Mockito.mock(MintLoadService.class);
        Mockito.when(loadService.load(any(UUID.class), Mockito.eq(false))).thenReturn(mint);
        return loadService;
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
}
