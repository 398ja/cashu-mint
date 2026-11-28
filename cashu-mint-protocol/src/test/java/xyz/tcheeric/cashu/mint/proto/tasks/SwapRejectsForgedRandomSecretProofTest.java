package xyz.tcheeric.cashu.mint.proto.tasks;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.KeysetId;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.PrivateKey;
import xyz.tcheeric.cashu.common.PublicKey;
import xyz.tcheeric.cashu.common.RSSProof;
import xyz.tcheeric.cashu.common.RandomStringSecret;
import xyz.tcheeric.cashu.common.Signature;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.crypto.BDHKEUtils;
import xyz.tcheeric.cashu.crypto.Schnorr;
import xyz.tcheeric.cashu.crypto.util.Utils;
import xyz.tcheeric.cashu.entities.rest.nut03.PostSwapRequest;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.voucher.InstalledVoucherIssuerBinding;
import xyz.tcheeric.cashu.mint.proto.voucher.VoucherDocumentContext;

/**
 * A random-secret input is rejected on the swap path when the mint never signed it.
 *
 * <p>{@code RSSSpendingCondition} no longer repeats the BDHKE check: {@link
 * xyz.tcheeric.cashu.mint.proto.tasks.validator.ProofAuthenticity} runs it for every input
 * before any condition. This test pins that the swap path still refuses a forged {@code C},
 * with real BDHKE and no mocked crypto, so removing the duplicate cannot have removed the gate.
 */
@DisplayName("Swap rejects a random-secret proof the mint never issued")
class SwapRejectsForgedRandomSecretProofTest {

    private static final String KEYSET_ID = "0123456789abcdef";

    private static MintProtocolService serviceHolding(PrivateKey mintKey) throws CashuErrorException {
        MintProtocolService service = Mockito.mock(MintProtocolService.class);
        Mockito.when(service.getPrivateKey(anyString(), anyInt(), any(Mint.class))).thenReturn(mintKey);
        return service;
    }

    private static RSSProof proof(RandomStringSecret secret, byte[] c) {
        RSSProof p = new RSSProof();
        p.setAmount(8);
        p.setKeySetId(KEYSET_ID);
        p.setSecret(secret);
        p.setUnblindedSignature(Signature.fromBytes(c));
        return p;
    }

    private static byte[] genuineC(RandomStringSecret secret, PrivateKey mintKey) {
        var y = BDHKEUtils.hashToCurve(secret.toString().getBytes(StandardCharsets.UTF_8));
        return BDHKEUtils.signBlindedMessage(y, Utils.bigIntFromBytes(mintKey.toBytes())).getEncoded(true);
    }

    @SuppressWarnings("unchecked")
    private static PostSwapRequest<RandomStringSecret> swapOf(RSSProof proof) {
        BlindedMessage out = new BlindedMessage();
        out.setAmount(8);
        out.setKeySetId(KeysetId.fromString(KEYSET_ID));
        out.setBlindedMessage(PublicKey.fromString(
                "02d963e52f9d2f9519f8adedc8517389293d8028e0b33c4bc96b5e3cd128c27af2"));
        PostSwapRequest<RandomStringSecret> request = Mockito.mock(PostSwapRequest.class);
        Mockito.when(request.getInputs()).thenReturn(List.of(proof));
        Mockito.when(request.getBlindedMessages()).thenReturn(List.of(out));
        return request;
    }

    private static VerifyProofsTask<RandomStringSecret> task(RSSProof proof, PrivateKey mintKey)
            throws CashuErrorException {
        return new VerifyProofsTask<>(new Mint(), swapOf(proof), serviceHolding(mintKey),
                InstalledVoucherIssuerBinding.current(), VoucherDocumentContext.none());
    }

    @Test
    @DisplayName("a forged C is rejected")
    void forgedCIsRejected() throws Exception {
        PrivateKey mintKey = PrivateKey.fromBytes(Schnorr.generatePrivateKey());
        byte[] garbageC = PrivateKey.derivePublicKey(PrivateKey.fromBytes(Schnorr.generatePrivateKey())).getBytes();
        assertThrows(CashuErrorException.class,
                task(proof(RandomStringSecret.create(), garbageC), mintKey)::execute);
    }

    @Test
    @DisplayName("C signed by another key is rejected")
    void otherKeysSignatureIsRejected() throws Exception {
        PrivateKey mintKey = PrivateKey.fromBytes(Schnorr.generatePrivateKey());
        PrivateKey otherKey = PrivateKey.fromBytes(Schnorr.generatePrivateKey());
        RandomStringSecret secret = RandomStringSecret.create();
        assertThrows(CashuErrorException.class,
                task(proof(secret, genuineC(secret, otherKey)), mintKey)::execute);
    }

    @Test
    @DisplayName("a genuinely issued proof still passes")
    void genuineProofPasses() throws Exception {
        PrivateKey mintKey = PrivateKey.fromBytes(Schnorr.generatePrivateKey());
        RandomStringSecret secret = RandomStringSecret.create();
        assertDoesNotThrow(task(proof(secret, genuineC(secret, mintKey)), mintKey)::execute);
    }
}
