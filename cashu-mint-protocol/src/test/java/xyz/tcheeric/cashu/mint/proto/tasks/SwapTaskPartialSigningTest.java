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
import xyz.tcheeric.cashu.mint.proto.domain.SwapHoldPhase;
import xyz.tcheeric.cashu.mint.proto.ports.SwapHoldRepository;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.MintVaultService;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultService;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultServiceMocks;
import xyz.tcheeric.cashu.mint.proto.service.SignatureVaultService;
import xyz.tcheeric.cashu.mint.proto.service.impl.MintProtocolServiceFactory;
import xyz.tcheeric.cashu.mint.proto.util.SignatureTestData;
import xyz.tcheeric.cashu.vault.db.model.MintEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * Issue #491: once the signature vault refuses an output already signed by someone else, a swap
 * can fail on its second output after its first is durably recorded.
 *
 * <p>The first output is then recoverable through NUT-09 restore, so releasing the inputs would
 * let the same value be redeemed twice. The hold must be spent instead, exactly as it is when
 * signing completes.
 */
class SwapTaskPartialSigningTest {

    private static final String KEYSET_ID = "0123456789abcdef";
    private static final List<String> BLINDED_MESSAGES = List.of(
            "02d963e52f9d2f9519f8adedc8517389293d8028e0b33c4bc96b5e3cd128c27af2",
            "03a0434d9e47f3c86235477c7b1ae6ae5d3442d49b1943c2b752a68e2a47e247c7");

    /**
     * A swap whose second output is refused as already signed spends its inputs, because its
     * first output is already signed and recoverable.
     */
    @Test
    void aSwapRefusedAfterItsFirstSignatureSpendsItsInputsInsteadOfReleasingThem() throws CashuErrorException {
        PhaseRecordingHoldRepository holds = new PhaseRecordingHoldRepository();
        ProofVaultService proofVault = proofVaultSpendingEverything();

        assertThatThrownBy(() -> executeSwap(proofVault, holds, 1))
                .isInstanceOf(CashuErrorException.class)
                .extracting(e -> ((CashuErrorException) e).getErrorCode())
                .isEqualTo(CashuErrorCode.outputs_already_signed);

        assertThat(holds.phases)
                .as("an output is signed, so the inputs must not become spendable again")
                .doesNotContain(SwapHoldPhase.RELEASED)
                .contains(SwapHoldPhase.COMMITTED);
        Mockito.verify(proofVault).commitSpentForHold(anyString());
    }

    /** A swap refused on its very first output has signed nothing, so it returns the inputs. */
    @Test
    void aSwapRefusedBeforeAnySignatureReleasesItsInputs() throws CashuErrorException {
        PhaseRecordingHoldRepository holds = new PhaseRecordingHoldRepository();
        ProofVaultService proofVault = proofVaultSpendingEverything();

        assertThatThrownBy(() -> executeSwap(proofVault, holds, 0))
                .isInstanceOf(CashuErrorException.class)
                .extracting(e -> ((CashuErrorException) e).getErrorCode())
                .isEqualTo(CashuErrorCode.outputs_already_signed);

        assertThat(holds.phases)
                .as("nothing was signed, so the wallet keeps its money")
                .contains(SwapHoldPhase.RELEASED)
                .doesNotContain(SwapHoldPhase.COMMITTED);
        Mockito.verify(proofVault, Mockito.never()).commitSpentForHold(anyString());
    }

    // --- harness ---------------------------------------------------------------------------

    private static final class PhaseRecordingHoldRepository implements SwapHoldRepository {

        private final List<SwapHoldPhase> phases = new ArrayList<>();

        @Override
        public void open(String holdId, int inputCount) {
        }

        @Override
        public void advance(String holdId, SwapHoldPhase phase) {
            phases.add(phase);
        }
    }

    private static ProofVaultService proofVaultSpendingEverything() throws CashuErrorException {
        ProofVaultService proofVault = ProofVaultServiceMocks.keyingProofsByIssuanceKey();
        Mockito.when(proofVault.insertOrClaimForHold(any(), anyString(), any()))
                .thenAnswer(invocation -> ((List<?>) invocation.getArgument(0)).size());
        Mockito.when(proofVault.commitSpentForHold(anyString())).thenReturn(1);
        return proofVault;
    }

    /**
     * @param signaturesBeforeRefusal how many outputs sign successfully before the next one is
     *                                refused as already signed
     */
    private void executeSwap(ProofVaultService proofVault,
                             PhaseRecordingHoldRepository holds,
                             int signaturesBeforeRefusal) throws CashuErrorException {
        MintProtocolService service = Mockito.mock(MintProtocolService.class);
        MintVaultService mintVault = Mockito.mock(MintVaultService.class);
        Mockito.when(mintVault.retrieveMint(anyString())).thenReturn(new MintEntity());
        AtomicInteger signed = new AtomicInteger();

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
                             if (signed.getAndIncrement() >= signaturesBeforeRefusal) {
                                 throw new CashuErrorException(CashuErrorCode.outputs_already_signed);
                             }
                             return new BlindSignature(output.getAmount(),
                                     KeysetId.fromString(KEYSET_ID),
                                     SignatureTestData.sampleSignature(), null);
                         }).when(mock).execute();
                     })) {

            factory.when(MintProtocolServiceFactory::getInstance).thenReturn(service);

            new SwapTask<>(UUID.randomUUID(), twoOutputRequest(), mintLoadService(),
                    Mockito.mock(SignatureVaultService.class), mintVault, proofVault, holds).execute();
        }
    }

    private MintLoadService mintLoadService() throws CashuErrorException {
        Mint mint = new Mint(UUID.randomUUID().toString());
        mint.addKeySet(KeySet.builder().id(KEYSET_ID).unit("sat").build());
        MintLoadService loadService = Mockito.mock(MintLoadService.class);
        Mockito.when(loadService.load(any(UUID.class), Mockito.eq(false))).thenReturn(mint);
        return loadService;
    }

    private PostSwapRequest<RandomStringSecret> twoOutputRequest() {
        RSSProof proof = new RSSProof();
        proof.setAmount(16);
        proof.setKeySetId(KEYSET_ID);
        proof.setSecret(RandomStringSecret.create());
        proof.setUnblindedSignature(SignatureTestData.sampleSignature());

        List<BlindedMessage> outputs = new ArrayList<>();
        for (String blindedMessage : BLINDED_MESSAGES) {
            BlindedMessage output = new BlindedMessage();
            output.setAmount(8);
            output.setKeySetId(KeysetId.fromString(KEYSET_ID));
            output.setBlindedMessage(PublicKey.fromString(blindedMessage));
            outputs.add(output);
        }

        PostSwapRequest<RandomStringSecret> request = new PostSwapRequest<>();
        request.setInputs(List.<Proof<RandomStringSecret>>of(proof));
        request.setBlindedMessages(outputs);
        return request;
    }
}
