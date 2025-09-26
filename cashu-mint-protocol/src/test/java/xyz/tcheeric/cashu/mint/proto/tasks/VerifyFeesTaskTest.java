package xyz.tcheeric.cashu.mint.proto.tasks;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.common.BlindSignature;
import xyz.tcheeric.cashu.common.KeySet;
import xyz.tcheeric.cashu.common.KeysetId;
import xyz.tcheeric.cashu.common.RSSProof;
import xyz.tcheeric.cashu.common.RandomStringSecret;
import xyz.tcheeric.cashu.common.Witness;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.rest.PostSwapRequest;
import xyz.tcheeric.cashu.entities.rest.PostSwapResponse;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.mint.proto.util.SignatureTestData;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;

public class VerifyFeesTaskTest {

    private static final String VALID_KEYSET_ID = "0123456789abcdef";

    private RSSProof createProof(int amount) {
        RSSProof proof = new RSSProof();
        proof.setAmount(amount);
        proof.setKeySetId("ks1");
        proof.setSecret(RandomStringSecret.create());
        proof.setUnblindedSignature(SignatureTestData.sampleSignature());
        proof.setWitness(new Witness());
        return proof;
    }

    private BlindSignature createSignature(int amount) {
        return new BlindSignature(amount, KeysetId.fromString(VALID_KEYSET_ID), SignatureTestData.sampleSignature());
    }

    /**
     * Checks that fee validation passes when proofs, fees, and responses align.
     */
    @Test
    public void executeSuccess() throws CashuErrorException {
        PostSwapRequest<RandomStringSecret> request = Mockito.mock(PostSwapRequest.class);
        PostSwapResponse response = Mockito.mock(PostSwapResponse.class);
        MintLoadService mintLoadService = Mockito.mock(MintLoadService.class);

        RSSProof proof = createProof(10);
        BlindSignature sig = createSignature(10);

        Mockito.when(request.getInputs()).thenReturn(List.of(proof));
        Mockito.when(request.getFees(any(KeySet.class))).thenReturn(0);
        Mockito.when(response.getBlindSignatures()).thenReturn(List.of(sig));

        KeySet keySet = KeySet.builder().id("ks1").unit("sat").partPerThousand(0).build();
        Mockito.when(mintLoadService.keySets()).thenReturn(List.of(keySet));

        VerifyFeesTask<RandomStringSecret> task = new VerifyFeesTask<>(request, response, mintLoadService);
        assertDoesNotThrow(task::execute);
    }

    /**
     * Ensures the task throws when the mint would create value out of thin air.
     */
    @Test
    public void executeRejectsExcessOutputs() throws CashuErrorException {
        PostSwapRequest<RandomStringSecret> request = Mockito.mock(PostSwapRequest.class);
        PostSwapResponse response = Mockito.mock(PostSwapResponse.class);
        MintLoadService mintLoadService = Mockito.mock(MintLoadService.class);

        RSSProof proof = createProof(10);
        BlindSignature sig = createSignature(15);

        Mockito.when(request.getInputs()).thenReturn(List.of(proof));
        Mockito.when(request.getFees(any(KeySet.class))).thenReturn(1);
        Mockito.when(response.getBlindSignatures()).thenReturn(List.of(sig));

        KeySet keySet = KeySet.builder().id("ks1").unit("sat").partPerThousand(0).build();
        Mockito.when(mintLoadService.keySets()).thenReturn(List.of(keySet));

        VerifyFeesTask<RandomStringSecret> task = new VerifyFeesTask<>(request, response, mintLoadService);
        assertThrows(CashuErrorException.class, task::execute);
    }

    /**
     * Verifies mismatched declared fees no longer abort the swap when outputs are covered by inputs.
     */
    @Test
    public void executeAllowsFeeMismatch() throws CashuErrorException {
        PostSwapRequest<RandomStringSecret> request = Mockito.mock(PostSwapRequest.class);
        PostSwapResponse response = Mockito.mock(PostSwapResponse.class);
        MintLoadService mintLoadService = Mockito.mock(MintLoadService.class);

        RSSProof proof = createProof(10);
        BlindSignature sig = createSignature(10);

        Mockito.when(request.getInputs()).thenReturn(List.of(proof));
        Mockito.when(request.getFees(any(KeySet.class))).thenReturn(5);
        Mockito.when(response.getBlindSignatures()).thenReturn(List.of(sig));

        KeySet keySet = KeySet.builder().id("ks1").unit("sat").partPerThousand(0).build();
        Mockito.when(mintLoadService.keySets()).thenReturn(List.of(keySet));

        VerifyFeesTask<RandomStringSecret> task = new VerifyFeesTask<>(request, response, mintLoadService);
        assertDoesNotThrow(task::execute);
    }
}
