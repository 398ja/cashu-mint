package xyz.tcheeric.cashu.mint.proto.tasks;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.common.BlindSignature;
import xyz.tcheeric.cashu.common.KeySet;
import xyz.tcheeric.cashu.common.RandomStringSecret;
import xyz.tcheeric.cashu.common.RSSProof;
import xyz.tcheeric.cashu.common.Signature;
import xyz.tcheeric.cashu.common.Witness;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.mint.proto.nut.NUT02;
import xyz.tcheeric.cashu.entities.rest.PostSwapRequest;
import xyz.tcheeric.cashu.entities.rest.PostSwapResponse;
import xyz.tcheeric.cashu.mint.proto.util.MintProtocolUtil;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;

public class VerifyFeesTaskTest {

    private RSSProof createProof(int amount) {
        RSSProof proof = new RSSProof();
        proof.setAmount(amount);
        proof.setKeySetId("ks1");
        proof.setSecret(RandomStringSecret.create());
        proof.setUnblindedSignature(Signature.fromString(MintProtocolUtil.createRandomBytes(33)));
        proof.setWitness(new Witness());
        return proof;
    }

    private BlindSignature createSignature(int amount) {
        return new BlindSignature(amount, "ks1", Signature.fromString(MintProtocolUtil.createRandomBytes(33)));
    }

    @Test
    public void executeSuccess() throws CashuErrorException {
        PostSwapRequest<RandomStringSecret> request = Mockito.mock(PostSwapRequest.class);
        PostSwapResponse response = Mockito.mock(PostSwapResponse.class);

        RSSProof proof = createProof(10);
        BlindSignature sig = createSignature(10);

        Mockito.when(request.getInputs()).thenReturn(List.of(proof));
        Mockito.when(request.getFees(any(KeySet.class))).thenReturn(0);
        Mockito.when(response.getBlindSignatures()).thenReturn(List.of(sig));

        KeySet keySet = KeySet.builder().id("ks1").unit("sat").partPerThousand(0).build();
        try (MockedStatic<NUT02> nut = Mockito.mockStatic(NUT02.class)) {
            nut.when(() -> NUT02.keys(any(UUID.class))).thenReturn(keySet);

            VerifyFeesTask<RandomStringSecret> task = new VerifyFeesTask<>(request, response);
            assertDoesNotThrow(task::execute);
        }
    }

    @Test
    public void executeFailure() throws CashuErrorException {
        PostSwapRequest<RandomStringSecret> request = Mockito.mock(PostSwapRequest.class);
        PostSwapResponse response = Mockito.mock(PostSwapResponse.class);

        RSSProof proof = createProof(10);
        BlindSignature sig = createSignature(5);

        Mockito.when(request.getInputs()).thenReturn(List.of(proof));
        Mockito.when(request.getFees(any(KeySet.class))).thenReturn(1);
        Mockito.when(response.getBlindSignatures()).thenReturn(List.of(sig));

        KeySet keySet = KeySet.builder().id("ks1").unit("sat").partPerThousand(0).build();
        try (MockedStatic<NUT02> nut = Mockito.mockStatic(NUT02.class)) {
            nut.when(() -> NUT02.keys(any(UUID.class))).thenReturn(keySet);

            VerifyFeesTask<RandomStringSecret> task = new VerifyFeesTask<>(request, response);
            assertThrows(CashuErrorException.class, task::execute);
        }
    }
}

