package xyz.tcheeric.cashu.mint.proto.tasks;

import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.RSSProof;
import xyz.tcheeric.cashu.common.RandomStringSecret;
import xyz.tcheeric.cashu.common.Signature;
import xyz.tcheeric.cashu.common.Witness;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.rest.PostSwapRequest;
import xyz.tcheeric.cashu.mint.proto.tasks.validator.RSSSpendingCondition;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;

public class VerifyProofsTaskTest {

    private RSSProof createProof(int amount) {
        RSSProof proof = new RSSProof();
        proof.setAmount(amount);
        proof.setKeySetId("ks1");
        proof.setSecret(RandomStringSecret.create());
        proof.setUnblindedSignature(Signature.fromString("00"));
        proof.setWitness(new Witness());
        return proof;
    }

    private BlindedMessage createBlindedMessage(int amount) {
        BlindedMessage bm = new BlindedMessage();
        bm.setAmount(amount);
        bm.setKeySetId("ks1");
        bm.setBlindedMessage(null);
        bm.setWitness(new Witness());
        return bm;
    }

    @Test
    public void executeSuccess() throws CashuErrorException {
        Mint mint = new Mint();
        PostSwapRequest<RandomStringSecret> request = Mockito.mock(PostSwapRequest.class);

        RSSProof proof = createProof(10);
        BlindedMessage bm = createBlindedMessage(10);

        Mockito.when(request.getInputs()).thenReturn(List.of(proof));
        Mockito.when(request.getBlindedMessages()).thenReturn(List.of(bm));

        try (MockedConstruction<RSSSpendingCondition> cons = Mockito.mockConstruction(RSSSpendingCondition.class,
                (mock, ctx) -> Mockito.doNothing().when(mock).verify(any()))) {
            VerifyProofsTask<RandomStringSecret> task = new VerifyProofsTask<>(mint, request);
            assertDoesNotThrow(task::execute);
            Mockito.verify(cons.constructed().get(0)).verify(proof);
        }
    }

    @Test
    public void executeFailAmounts() {
        Mint mint = new Mint();
        PostSwapRequest<RandomStringSecret> request = Mockito.mock(PostSwapRequest.class);

        RSSProof proof = createProof(10);
        BlindedMessage bm = createBlindedMessage(5);

        Mockito.when(request.getInputs()).thenReturn(List.of(proof));
        Mockito.when(request.getBlindedMessages()).thenReturn(List.of(bm));

        VerifyProofsTask<RandomStringSecret> task = new VerifyProofsTask<>(mint, request);
        assertThrows(CashuErrorException.class, task::execute);
    }

    @Test
    public void executeFailVerification() throws CashuErrorException {
        Mint mint = new Mint();
        PostSwapRequest<RandomStringSecret> request = Mockito.mock(PostSwapRequest.class);

        RSSProof proof = createProof(10);
        BlindedMessage bm = createBlindedMessage(10);

        Mockito.when(request.getInputs()).thenReturn(List.of(proof));
        Mockito.when(request.getBlindedMessages()).thenReturn(List.of(bm));

        try (MockedConstruction<RSSSpendingCondition> cons = Mockito.mockConstruction(RSSSpendingCondition.class,
                (mock, ctx) -> Mockito.doThrow(new CashuErrorException("fail")).when(mock).verify(any()))) {
            VerifyProofsTask<RandomStringSecret> task = new VerifyProofsTask<>(mint, request);
            assertThrows(CashuErrorException.class, task::execute);
        }
    }
}

