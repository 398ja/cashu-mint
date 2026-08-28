package xyz.tcheeric.cashu.mint.proto.tasks;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.KeySet;
import xyz.tcheeric.cashu.common.nut02.KeySetResolver;
import xyz.tcheeric.cashu.common.KeysetId;
import xyz.tcheeric.cashu.common.PublicKey;
import xyz.tcheeric.cashu.common.RSSProof;
import xyz.tcheeric.cashu.common.RandomStringSecret;
import xyz.tcheeric.cashu.common.Witness;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.rest.nut03.PostSwapRequest;
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

    private BlindedMessage createOutput(int amount) {
        BlindedMessage output = new BlindedMessage();
        output.setAmount(amount);
        output.setKeySetId(KeysetId.fromString(VALID_KEYSET_ID));
        output.setBlindedMessage(PublicKey.fromString(
                "02d963e52f9d2f9519f8adedc8517389293d8028e0b33c4bc96b5e3cd128c27af2"));
        return output;
    }

    /**
     * Checks that the balance equation passes when inputs, fees and requested outputs align.
     * The equation is read from the request, because it is checked before anything is signed.
     */
    @Test
    public void executeSuccess() throws CashuErrorException {
        PostSwapRequest<RandomStringSecret> request = Mockito.mock(PostSwapRequest.class);
        MintLoadService mintLoadService = Mockito.mock(MintLoadService.class);

        RSSProof proof = createProof(10);
        BlindedMessage output = createOutput(10);

        Mockito.when(request.getInputs()).thenReturn(List.of(proof));
        Mockito.when(request.getFees(any(KeySetResolver.class))).thenReturn(0);
        Mockito.when(request.getBlindedMessages()).thenReturn(List.of(output));

        KeySet keySet = KeySet.builder().id("ks1").unit("sat").partPerThousand(0).build();
        Mockito.when(mintLoadService.keySets()).thenReturn(List.of(keySet));

        VerifyFeesTask<RandomStringSecret> task = new VerifyFeesTask<>(request, mintLoadService);
        assertDoesNotThrow(task::execute);
    }

    /**
     * Ensures an unbalanced request is refused: inputs minus fees must equal the outputs.
     */
    @Test
    public void executeFailure() throws CashuErrorException {
        PostSwapRequest<RandomStringSecret> request = Mockito.mock(PostSwapRequest.class);
        MintLoadService mintLoadService = Mockito.mock(MintLoadService.class);

        RSSProof proof = createProof(10);
        BlindedMessage output = createOutput(5);

        Mockito.when(request.getInputs()).thenReturn(List.of(proof));
        Mockito.when(request.getFees(any(KeySetResolver.class))).thenReturn(1);
        Mockito.when(request.getBlindedMessages()).thenReturn(List.of(output));

        KeySet keySet = KeySet.builder().id("ks1").unit("sat").partPerThousand(0).build();
        Mockito.when(mintLoadService.keySets()).thenReturn(List.of(keySet));

        VerifyFeesTask<RandomStringSecret> task = new VerifyFeesTask<>(request, mintLoadService);
        assertThrows(CashuErrorException.class, task::execute);
    }
}
