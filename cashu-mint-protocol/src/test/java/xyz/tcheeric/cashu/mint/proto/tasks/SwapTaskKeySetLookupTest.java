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
 * A swap must resolve the mint's keysets once, not once per input and per output.
 *
 * <p>Each keyset load is O(keys) HTTP calls to the vault, because the private material of every
 * key is fetched individually. Staging measured ~98 vault key GETs for a single swap with the
 * same keyset refetched 5-8 times, and p99 latency of 6.29s that did not track signature count.
 * The cost was the repeated I/O.
 *
 * <p>Both directions are asserted here. Making the lookups cheap is only correct if the IOU
 * rejection it feeds still refuses a real IOU keyset, so that rule is exercised against the same
 * cached path rather than assumed.
 */
public class SwapTaskKeySetLookupTest {

    private static final String SAT_KEYSET_ID = "0123456789abcdef";
    private static final String IOU_KEYSET_ID = "0011aa22bb33cc44";

    /** Distinct keys, so a fixture of several outputs is not itself a duplicate-output rejection. */
    private static final List<String> BLINDED_MESSAGES = List.of(
            "02d963e52f9d2f9519f8adedc8517389293d8028e0b33c4bc96b5e3cd128c27af2",
            "03a0434d9e47f3c86235477c7b1ae6ae5d3442d49b1943c2b752a68e2a47e247c7",
            "025f9d298d8d9e774c81ee64927a27e6e6b6e18f65447eb6a16808f92b84e44112");

    /**
     * Counts the vault-backed loads a swap performs, which is what the defect was measured in.
     *
     * <p>{@code load(boolean)} is the only method that reaches the vault: the {@code keySets}
     * methods on the interface are defaults built on top of it, so counting here counts every
     * keyset read however it was spelled.
     */
    private static final class CountingMintLoadService implements MintLoadService {

        private final Mint mint;
        private final AtomicInteger vaultLoads = new AtomicInteger();

        private CountingMintLoadService(Mint mint) {
            this.mint = mint;
        }

        @Override
        public Mint load(UUID mintId, boolean archive) {
            return mint;
        }

        @Override
        public List<Mint> load(boolean archive) {
            vaultLoads.incrementAndGet();
            return archive ? List.of() : List.of(mint);
        }

        private int vaultLoads() {
            return vaultLoads.get();
        }
    }

    private Mint mintWith(KeySet... keySets) {
        Mint mint = new Mint();
        for (KeySet keySet : keySets) {
            mint.addKeySet(keySet);
        }
        return mint;
    }

    private KeySet keySet(String id, String unit) {
        Keys keys = new Keys();
        keys.put(BigInteger.ZERO, PublicKey.fromString(BLINDED_MESSAGES.get(0)));
        return KeySet.builder().id(id).unit(unit).keys(keys).build();
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

    private PostSwapRequest<RandomStringSecret> swapOf(int itemCount, String keySetId) {
        List<RSSProof> inputs = new ArrayList<>();
        List<BlindedMessage> outputs = new ArrayList<>();
        for (int item = 0; item < itemCount; item++) {
            inputs.add(proof(keySetId));
            outputs.add(output(keySetId, item));
        }
        PostSwapRequest<RandomStringSecret> request = new PostSwapRequest<>();
        request.setInputs(List.copyOf(inputs));
        request.setBlindedMessages(outputs);
        return request;
    }

    /** Runs a swap to completion with everything but the keyset reads stubbed out. */
    private void executeSwap(PostSwapRequest<RandomStringSecret> request, MintLoadService loadService)
            throws CashuErrorException {
        try (MockedStatic<MintProtocolServiceFactory> factory =
                     Mockito.mockStatic(MintProtocolServiceFactory.class);
             MockedConstruction<VerifyProofsTask> verify = Mockito.mockConstruction(VerifyProofsTask.class,
                     (mock, context) -> Mockito.doNothing().when(mock).execute());
             MockedConstruction<SwapProofHold> hold = Mockito.mockConstruction(SwapProofHold.class);
             MockedConstruction<VerifyFeesTask> fees = Mockito.mockConstruction(VerifyFeesTask.class,
                     (mock, context) -> Mockito.doNothing().when(mock).execute());
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
     * The number of keyset loads does not grow with the size of the swap.
     *
     * <p>This is the defect stated as a test. Before the fix the cost was 5 loads per
     * input/output pair (two to resolve the input's unit, two for the output's, one for the
     * archived check), measured at 5, 10 and 15 loads for 1, 2 and 3 pairs. Asserting that the
     * count is identical for a 1-item and a 3-item swap fails on any return to per-item
     * resolution, whatever the constant happens to be.
     */
    @Test
    public void resolvesKeySetsOnceRegardlessOfSwapSize() throws CashuErrorException {
        CountingMintLoadService smallSwap =
                new CountingMintLoadService(mintWith(keySet(SAT_KEYSET_ID, "sat")));
        executeSwap(swapOf(1, SAT_KEYSET_ID), smallSwap);

        CountingMintLoadService largeSwap =
                new CountingMintLoadService(mintWith(keySet(SAT_KEYSET_ID, "sat")));
        executeSwap(swapOf(3, SAT_KEYSET_ID), largeSwap);

        assertEquals(smallSwap.vaultLoads(), largeSwap.vaultLoads(),
                "keyset loads must not scale with the number of inputs and outputs");
    }

    /**
     * One swap reads each keyset generation at most once.
     *
     * <p>The generations are active and archived, so two loads is the floor for a swap that has
     * to answer whether an output's keyset is retired. Pinning the absolute ceiling catches a
     * regression that keeps the count constant in swap size but reintroduces repeated reads.
     */
    @Test
    public void readsEachKeySetGenerationAtMostOnce() throws CashuErrorException {
        CountingMintLoadService loadService =
                new CountingMintLoadService(mintWith(keySet(SAT_KEYSET_ID, "sat")));

        executeSwap(swapOf(3, SAT_KEYSET_ID), loadService);

        assertTrue(loadService.vaultLoads() <= 2,
                "expected at most one load per keyset generation, got " + loadService.vaultLoads());
    }

    /**
     * The IOU keyset is still refused, and refused through the now-cached lookup path.
     *
     * <p>The rejection is the reason the lookup exists. A cheaper lookup that stopped refusing
     * IOU inputs would let zero-value loan markers be swapped for real value.
     */
    @Test
    public void stillRefusesAnInputFromTheIouKeySet() throws CashuErrorException {
        CountingMintLoadService loadService = new CountingMintLoadService(
                mintWith(keySet(SAT_KEYSET_ID, "sat"), keySet(IOU_KEYSET_ID, "iou")));

        CashuErrorException rejection = assertThrows(CashuErrorException.class,
                () -> executeSwap(swapOf(1, IOU_KEYSET_ID), loadService));

        assertEquals(CashuErrorCode.iou_not_swappable, rejection.getErrorCode());
    }

    /**
     * Swapping into the IOU keyset is refused on the output side too.
     *
     * <p>Inputs and outputs are separate loops over the same resolved set, so the output rule
     * has to be exercised separately: indexing the ids correctly for one and not the other is a
     * live failure mode.
     */
    @Test
    public void stillRefusesAnOutputIntoTheIouKeySet() throws CashuErrorException {
        CountingMintLoadService loadService = new CountingMintLoadService(
                mintWith(keySet(SAT_KEYSET_ID, "sat"), keySet(IOU_KEYSET_ID, "iou")));

        PostSwapRequest<RandomStringSecret> request = new PostSwapRequest<>();
        request.setInputs(List.of(proof(SAT_KEYSET_ID)));
        request.setBlindedMessages(List.of(output(IOU_KEYSET_ID, 0)));

        CashuErrorException rejection = assertThrows(CashuErrorException.class,
                () -> executeSwap(request, loadService));

        assertEquals(CashuErrorCode.iou_not_swappable, rejection.getErrorCode());
    }

    /**
     * A mint that has no IOU keyset still swaps its ordinary keyset freely.
     *
     * <p>Guards the opposite error to the two above: a rejection rule driven by a cached set is
     * only correct if it refuses the IOU keyset and nothing else.
     */
    @Test
    public void allowsAnOrdinaryKeySetWhenAnIouKeySetAlsoExists() throws CashuErrorException {
        CountingMintLoadService loadService = new CountingMintLoadService(
                mintWith(keySet(SAT_KEYSET_ID, "sat"), keySet(IOU_KEYSET_ID, "iou")));

        executeSwap(swapOf(2, SAT_KEYSET_ID), loadService);
    }
}
