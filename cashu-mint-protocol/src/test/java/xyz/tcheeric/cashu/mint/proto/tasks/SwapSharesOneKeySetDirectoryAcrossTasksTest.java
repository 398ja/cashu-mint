package xyz.tcheeric.cashu.mint.proto.tasks;

import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.common.BlindSignature;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.KeySet;
import xyz.tcheeric.cashu.common.Keys;
import xyz.tcheeric.cashu.common.KeysetId;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.PublicKey;
import xyz.tcheeric.cashu.common.RSSProof;
import xyz.tcheeric.cashu.common.RandomStringSecret;
import xyz.tcheeric.cashu.common.nut00.CashuErrorCode;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.rest.nut03.PostSwapRequest;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.impl.DefaultSignatureVaultService;
import xyz.tcheeric.cashu.mint.proto.service.impl.MintProtocolServiceFactory;
import xyz.tcheeric.cashu.mint.proto.util.SignatureTestData;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One request reads each keyset generation at most once, across every task that serves it.
 *
 * <p>This is the gap that made 0.38.8 look finished. {@code KeySetDirectory} collapsed the reads
 * <em>within</em> one task, and {@code SwapTaskKeySetLookupTest} proved that by stubbing out
 * {@code VerifyFeesTask} and counting only the validation task's reads. On staging the live number
 * was still 22.5 keyset loads and 87 vault key GETs per swap, because each task built a directory
 * of its own: {@code ValidateTransactionTask} got one, and the NUT-02 fee arithmetic built a
 * second resolver behind {@code VerifyFeesTask}.
 *
 * <p>So the fee task runs for real here. Both tasks are driven through one {@code SwapTask}, the
 * count is taken over the whole request, and the assertion is the request-level ceiling rather
 * than a per-task one. Stubbing the fee task, as the earlier test does, is exactly what hid the
 * defect.
 */
public class SwapSharesOneKeySetDirectoryAcrossTasksTest {

    private static final String SAT_KEYSET_ID = "0123456789abcdef";

    /** Distinct keys, so a multi-output fixture is not itself a duplicate-output rejection. */
    private static final List<String> BLINDED_MESSAGES = List.of(
            "02d963e52f9d2f9519f8adedc8517389293d8028e0b33c4bc96b5e3cd128c27af2",
            "03a0434d9e47f3c86235477c7b1ae6ae5d3442d49b1943c2b752a68e2a47e247c7",
            "025f9d298d8d9e774c81ee64927a27e6e6b6e18f65447eb6a16808f92b84e44112");

    /**
     * Counts the vault-backed generation loads one request performs.
     *
     * <p>{@code load(boolean)} is the only method that reaches the vault: every {@code keySets}
     * method on the interface is a default built on top of it, so counting here counts every
     * keyset read however it was spelled, by whichever task spelled it.
     */
    private static final class CountingMintLoadService implements MintLoadService {

        private final Mint mint;
        private final AtomicInteger generationLoads = new AtomicInteger();

        private CountingMintLoadService(Mint mint) {
            this.mint = mint;
        }

        @Override
        public Mint load(UUID mintId, boolean archive) {
            return mint;
        }

        @Override
        public List<Mint> load(boolean archive) {
            generationLoads.incrementAndGet();
            return archive ? List.of() : List.of(mint);
        }

        private int generationLoads() {
            return generationLoads.get();
        }
    }

    private Mint mintWith(KeySet... keySets) {
        Mint mint = new Mint();
        for (KeySet keySet : keySets) {
            mint.addKeySet(keySet);
        }
        return mint;
    }

    /** A zero-fee keyset, so the balance equation holds for equal input and output sums. */
    private KeySet freeKeySet(String id, String unit) {
        Keys keys = new Keys();
        keys.put(BigInteger.ZERO, PublicKey.fromString(BLINDED_MESSAGES.get(0)));
        return KeySet.builder().id(id).unit(unit).keys(keys).partPerThousand(0).build();
    }

    private RSSProof proof(String keySetId) {
        RSSProof proof = new RSSProof();
        proof.setAmount(1);
        proof.setKeySetId(keySetId);
        proof.setSecret(RandomStringSecret.create());
        proof.setUnblindedSignature(SignatureTestData.sampleSignature());
        return proof;
    }

    private BlindedMessage output(String keySetId, int blindedMessageIndex) {
        BlindedMessage output = new BlindedMessage();
        output.setAmount(1);
        output.setKeySetId(KeysetId.fromString(keySetId));
        output.setBlindedMessage(PublicKey.fromString(BLINDED_MESSAGES.get(blindedMessageIndex)));
        return output;
    }

    /** A balanced swap of {@code itemCount} one-sat inputs for the same number of one-sat outputs. */
    private PostSwapRequest<RandomStringSecret> balancedSwapOf(int itemCount) {
        List<RSSProof> inputs = new ArrayList<>();
        List<BlindedMessage> outputs = new ArrayList<>();
        for (int item = 0; item < itemCount; item++) {
            inputs.add(proof(SAT_KEYSET_ID));
            outputs.add(output(SAT_KEYSET_ID, item));
        }
        PostSwapRequest<RandomStringSecret> request = new PostSwapRequest<>();
        request.setInputs(List.copyOf(inputs));
        request.setBlindedMessages(outputs);
        return request;
    }

    /**
     * Runs a swap with the keyset-reading tasks left real.
     *
     * <p>{@code ValidateTransactionTask} and {@code VerifyFeesTask} are the two tasks that read
     * keysets, and both run here. Only the collaborators that need a vault or real cryptography
     * are stubbed: proof verification, the input hold, and signing.
     */
    private void executeSwapWithFeesVerified(PostSwapRequest<RandomStringSecret> request,
                                             MintLoadService loadService) throws CashuErrorException {
        try (MockedStatic<MintProtocolServiceFactory> factory =
                     Mockito.mockStatic(MintProtocolServiceFactory.class);
             MockedConstruction<VerifyProofsTask> verify = Mockito.mockConstruction(VerifyProofsTask.class,
                     (mock, context) -> Mockito.doNothing().when(mock).execute());
             MockedConstruction<SwapProofHold> hold = Mockito.mockConstruction(SwapProofHold.class);
             MockedConstruction<SignBlindedMessageTask> sign =
                     Mockito.mockConstruction(SignBlindedMessageTask.class,
                             (mock, context) -> Mockito.doReturn(new BlindSignature(
                                     1,
                                     KeysetId.fromString(SAT_KEYSET_ID),
                                     SignatureTestData.sampleSignature(),
                                     null)).when(mock).execute())) {

            factory.when(MintProtocolServiceFactory::getInstance)
                    .thenReturn(Mockito.mock(MintProtocolService.class));

            new SwapTask<>(UUID.randomUUID(), request, loadService, new DefaultSignatureVaultService())
                    .execute();
        }
    }

    /**
     * A swap whose validation and fee tasks both run still reads each generation at most once.
     *
     * <p>Two is the floor for a request that has to answer whether an output's keyset is retired,
     * so two is the ceiling asserted. Before the directory was shared this was 4: two generations
     * read by the validation task, two more by the fee arithmetic.
     */
    @Test
    public void readsEachGenerationOnceAcrossValidationAndFeeTasks() throws CashuErrorException {
        CountingMintLoadService loadService =
                new CountingMintLoadService(mintWith(freeKeySet(SAT_KEYSET_ID, "sat")));

        executeSwapWithFeesVerified(balancedSwapOf(3), loadService);

        assertTrue(loadService.generationLoads() <= 2,
                "one request must read each keyset generation at most once, got "
                        + loadService.generationLoads());
    }

    /**
     * The count does not grow with the size of the swap, with every keyset-reading task running.
     *
     * <p>Catches the other shape of the defect: a shared directory that is nevertheless rebuilt,
     * or a rule that resolves per item again, would make a 3-item swap cost more than a 1-item one.
     */
    @Test
    public void generationLoadsDoNotScaleWithSwapSize() throws CashuErrorException {
        CountingMintLoadService smallSwap =
                new CountingMintLoadService(mintWith(freeKeySet(SAT_KEYSET_ID, "sat")));
        executeSwapWithFeesVerified(balancedSwapOf(1), smallSwap);

        CountingMintLoadService largeSwap =
                new CountingMintLoadService(mintWith(freeKeySet(SAT_KEYSET_ID, "sat")));
        executeSwapWithFeesVerified(balancedSwapOf(3), largeSwap);

        assertEquals(smallSwap.generationLoads(), largeSwap.generationLoads(),
                "keyset loads must not scale with the number of inputs and outputs");
    }

    /**
     * The shared directory still prices fees, so an unbalanced swap is refused.
     *
     * <p>The saving is only correct if the fee task reaches the same keysets through the shared
     * snapshot that it used to reach through its own resolver. A directory that resolved nothing
     * would also read nothing, and would pass the two counting tests above while letting an
     * unbalanced swap through.
     */
    @Test
    public void stillRefusesAnUnbalancedSwapThroughTheSharedDirectory() {
        CountingMintLoadService loadService =
                new CountingMintLoadService(mintWith(freeKeySet(SAT_KEYSET_ID, "sat")));

        PostSwapRequest<RandomStringSecret> unbalanced = balancedSwapOf(2);
        unbalanced.getBlindedMessages().get(0).setAmount(5);

        CashuErrorException rejection = assertThrows(CashuErrorException.class,
                () -> executeSwapWithFeesVerified(unbalanced, loadService));

        assertEquals(CashuErrorCode.transaction_not_balanced, rejection.getErrorCode());
    }
}
