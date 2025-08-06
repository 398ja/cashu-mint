package xyz.tcheeric.cashu.mint.proto.tasks;

import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.KeysetId;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.PublicKey;
import xyz.tcheeric.cashu.common.RSSProof;
import xyz.tcheeric.cashu.common.RandomStringSecret;
import xyz.tcheeric.cashu.common.Signature;
import xyz.tcheeric.cashu.common.Witness;
import xyz.tcheeric.cashu.common.P2PKProof;
import xyz.tcheeric.cashu.common.P2PKSecret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.rest.PostSwapRequest;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.tasks.validator.RSSSpendingCondition;
import xyz.tcheeric.cashu.mint.proto.tasks.validator.P2PKSpendingCondition;
import xyz.tcheeric.cashu.mint.proto.util.MintProtocolUtil;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;

public class VerifyProofsTaskTest {

    private static final String VALID_KEYSET_ID = "0123456789abcdef";

    private RSSProof createProof(int amount) {
        RSSProof proof = new RSSProof();
        proof.setAmount(amount);
        proof.setKeySetId(VALID_KEYSET_ID);
        proof.setSecret(RandomStringSecret.create());
        proof.setUnblindedSignature(Signature.fromString(MintProtocolUtil.createRandomBytes(33)));
        proof.setWitness(new Witness());
        return proof;
    }

    private BlindedMessage createBlindedMessage(int amount) {
        BlindedMessage bm = new BlindedMessage();
        bm.setAmount(amount);
        bm.setKeySetId(KeysetId.fromString(VALID_KEYSET_ID));
        bm.setBlindedMessage(PublicKey.fromString("02d963e52f9d2f9519f8adedc8517389293d8028e0b33c4bc96b5e3cd128c27af2"));
        bm.setWitness(new Witness());
        return bm;
    }

    private P2PKProof createP2PKProof(int amount) {
        P2PKProof proof = new P2PKProof();
        proof.setAmount(amount);
        proof.setKeySetId(VALID_KEYSET_ID);
        P2PKSecret secret = new P2PKSecret(new byte[32]);
        secret.setNSigs(1);
        secret.setSigFlag(P2PKSecret.SignatureFlag.SIG_INPUTS);
        proof.setSecret(secret);
        proof.setUnblindedSignature(Signature.fromString(MintProtocolUtil.createRandomBytes(33)));
        proof.setWitness(new Witness());
        return proof;
    }

    @Test
    public void executeSuccess() throws CashuErrorException {
        Mint mint = new Mint();
        PostSwapRequest<RandomStringSecret> request = Mockito.mock(PostSwapRequest.class);
        MintProtocolService service = Mockito.mock(MintProtocolService.class);

        RSSProof proof = createProof(10);
        BlindedMessage bm = createBlindedMessage(10);

        Mockito.when(request.getInputs()).thenReturn(List.of(proof));
        Mockito.when(request.getBlindedMessages()).thenReturn(List.of(bm));

        try (MockedConstruction<RSSSpendingCondition> cons = Mockito.mockConstruction(RSSSpendingCondition.class,
                (mock, ctx) -> Mockito.doNothing().when(mock).verify(any()))) {
            VerifyProofsTask<RandomStringSecret> task = new VerifyProofsTask<>(mint, request, service);
            assertDoesNotThrow(task::execute);
            Mockito.verify(cons.constructed().get(0)).verify(proof);
        }
    }

    @Test
    public void executeFailAmounts() {
        Mint mint = new Mint();
        PostSwapRequest<RandomStringSecret> request = Mockito.mock(PostSwapRequest.class);
        MintProtocolService service = Mockito.mock(MintProtocolService.class);

        RSSProof proof = createProof(10);
        BlindedMessage bm = createBlindedMessage(5);

        Mockito.when(request.getInputs()).thenReturn(List.of(proof));
        Mockito.when(request.getBlindedMessages()).thenReturn(List.of(bm));

        VerifyProofsTask<RandomStringSecret> task = new VerifyProofsTask<>(mint, request, service);
        assertThrows(CashuErrorException.class, task::execute);
    }

    @Test
    public void executeFailVerification() throws CashuErrorException {
        Mint mint = new Mint();
        PostSwapRequest<RandomStringSecret> request = Mockito.mock(PostSwapRequest.class);
        MintProtocolService service = Mockito.mock(MintProtocolService.class);

        RSSProof proof = createProof(10);
        BlindedMessage bm = createBlindedMessage(10);

        Mockito.when(request.getInputs()).thenReturn(List.of(proof));
        Mockito.when(request.getBlindedMessages()).thenReturn(List.of(bm));

        try (MockedConstruction<RSSSpendingCondition> cons = Mockito.mockConstruction(RSSSpendingCondition.class,
                (mock, ctx) -> Mockito.doThrow(new CashuErrorException("fail")).when(mock).verify(any()))) {
            VerifyProofsTask<RandomStringSecret> task = new VerifyProofsTask<>(mint, request, service);
            assertThrows(CashuErrorException.class, task::execute);
        }
    }

    @Test
    public void executeP2PKSuccess() throws CashuErrorException {
        Mint mint = new Mint();
        PostSwapRequest<P2PKSecret> request = Mockito.mock(PostSwapRequest.class);
        MintProtocolService service = Mockito.mock(MintProtocolService.class);

        P2PKProof proof = createP2PKProof(10);
        BlindedMessage bm = createBlindedMessage(10);

        Mockito.when(request.getInputs()).thenReturn(List.of(proof));
        Mockito.when(request.getBlindedMessages()).thenReturn(List.of(bm));

        try (MockedConstruction<P2PKSpendingCondition> cons = Mockito.mockConstruction(P2PKSpendingCondition.class,
                (mock, ctx) -> Mockito.doNothing().when(mock).verify(any()))) {
            VerifyProofsTask<P2PKSecret> task = new VerifyProofsTask<>(mint, request, service);
            assertDoesNotThrow(task::execute);
            Mockito.verify(cons.constructed().get(0)).verify(proof);
        }
    }
}

