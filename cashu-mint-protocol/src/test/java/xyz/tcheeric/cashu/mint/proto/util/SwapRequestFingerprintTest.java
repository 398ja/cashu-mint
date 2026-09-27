package xyz.tcheeric.cashu.mint.proto.util;

import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.KeysetId;
import xyz.tcheeric.cashu.common.Proof;
import xyz.tcheeric.cashu.common.PublicKey;
import xyz.tcheeric.cashu.common.RSSProof;
import xyz.tcheeric.cashu.common.RandomStringSecret;
import xyz.tcheeric.cashu.common.Signature;
import xyz.tcheeric.cashu.common.Witness;
import xyz.tcheeric.cashu.entities.rest.nut03.PostSwapRequest;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #482: the NUT-19 cache key for {@code POST /v1/swap}.
 *
 * <p>The key decides whether a request is answered from the cache, so a key that is too broad
 * hands one wallet another wallet's signatures and a key that is too narrow turns a legitimate
 * replay into a refused swap. These pin both edges.
 */
class SwapRequestFingerprintTest {

    private static final String KEYSET_ID = "0123456789abcdef";
    private static final String B_ONE =
            "02d963e52f9d2f9519f8adedc8517389293d8028e0b33c4bc96b5e3cd128c27af2";
    private static final String B_TWO =
            "03a0434d9e47f3c86235477c7b1ae6ae5d3442d49b1943c2b752a68e2a47e247c7";
    private static final String C_ONE = SignatureTestData.sampleSignatureHex();
    private static final String C_TWO = SignatureTestData.alternateSignatureHex();

    // The same request fingerprinted twice gives the same key, and the key is a 64-char hex digest.
    @Test
    void anIdenticalRequestHasTheSameFingerprint() {
        Proof<RandomStringSecret> input = proof(8, "aa01", C_ONE);

        SwapRequestFingerprint first = SwapRequestFingerprint.of(request(List.of(input), outputs(B_ONE)));
        SwapRequestFingerprint second = SwapRequestFingerprint.of(request(List.of(input), outputs(B_ONE)));

        assertThat(first).isEqualTo(second);
        assertThat(first.hex()).hasSize(64).matches("[0-9a-f]+");
    }

    // The acceptance criterion of #482: identical outputs with different inputs are two swaps.
    @Test
    void identicalOutputsWithDifferentInputsHaveDifferentFingerprints() {
        SwapRequestFingerprint first = SwapRequestFingerprint.of(
                request(List.of(proof(8, "aa01", C_ONE)), outputs(B_ONE)));
        SwapRequestFingerprint second = SwapRequestFingerprint.of(
                request(List.of(proof(8, "bb02", C_ONE)), outputs(B_ONE)));

        assertThat(first).isNotEqualTo(second);
    }

    // Identical inputs with different outputs are also two different requests.
    @Test
    void identicalInputsWithDifferentOutputsHaveDifferentFingerprints() {
        Proof<RandomStringSecret> input = proof(8, "aa01", C_ONE);

        assertThat(SwapRequestFingerprint.of(request(List.of(input), outputs(B_ONE))))
                .isNotEqualTo(SwapRequestFingerprint.of(request(List.of(input), outputs(B_TWO))));
    }

    // A wallet that rebuilds its input list in another order is still replaying the same swap.
    @Test
    void inputOrderDoesNotChangeTheFingerprint() {
        Proof<RandomStringSecret> a = proof(8, "aa01", C_ONE);
        Proof<RandomStringSecret> b = proof(8, "bb02", C_TWO);

        assertThat(SwapRequestFingerprint.of(request(List.of(a, b), outputs(B_ONE))))
                .isEqualTo(SwapRequestFingerprint.of(request(List.of(b, a), outputs(B_ONE))));
    }

    // Output order changes the key: the response is positional, so a reordered replay must miss
    // rather than hand back signatures the wallet would pair with the wrong blinding factors.
    @Test
    void outputOrderChangesTheFingerprint() {
        Proof<RandomStringSecret> input = proof(8, "aa01", C_ONE);

        assertThat(SwapRequestFingerprint.of(request(List.of(input), outputs(B_ONE, B_TWO))))
                .isNotEqualTo(SwapRequestFingerprint.of(request(List.of(input), outputs(B_TWO, B_ONE))));
    }

    // A P2PK retry is re-signed with fresh randomness; the witness must not turn it into a miss.
    @Test
    void theWitnessDoesNotChangeTheFingerprint() {
        Proof<RandomStringSecret> signedOnce = proof(8, "aa01", C_ONE);
        signedOnce.setWitness(new Witness(List.of("aa".repeat(64))));
        Proof<RandomStringSecret> signedAgain = proof(8, "aa01", C_ONE);
        signedAgain.setWitness(new Witness(List.of("bb".repeat(64))));

        assertThat(SwapRequestFingerprint.of(request(List.of(signedOnce), outputs(B_ONE))))
                .isEqualTo(SwapRequestFingerprint.of(request(List.of(signedAgain), outputs(B_ONE))));
    }

    // Moving bytes between adjacent fields must not produce the same digest, which a naive
    // concatenation without length prefixes would allow.
    @Test
    void fieldBoundariesCannotBeShiftedToCollide() {
        Proof<RandomStringSecret> shortKeyset = proof(8, "abcd", C_ONE);
        Proof<RandomStringSecret> longKeyset = proof(8, "cd", C_ONE);
        longKeyset.setKeySetId(KEYSET_ID + "ab");

        assertThat(SwapRequestFingerprint.of(request(List.of(shortKeyset), outputs(B_ONE))))
                .isNotEqualTo(SwapRequestFingerprint.of(request(List.of(longKeyset), outputs(B_ONE))));
    }

    // Changing an input's amount or signature is a different request.
    @Test
    void inputAmountAndSignatureAreBothPartOfTheKey() {
        SwapRequestFingerprint original = SwapRequestFingerprint.of(
                request(List.of(proof(8, "aa01", C_ONE)), outputs(B_ONE)));

        assertThat(SwapRequestFingerprint.of(request(List.of(proof(16, "aa01", C_ONE)), outputs(B_ONE))))
                .isNotEqualTo(original);
        assertThat(SwapRequestFingerprint.of(request(List.of(proof(8, "aa01", C_TWO)), outputs(B_ONE))))
                .isNotEqualTo(original);
    }

    // A request with no inputs or outputs still fingerprints, so the lookup path never throws.
    @Test
    void aRequestMissingItsListsStillFingerprints() {
        assertThat(SwapRequestFingerprint.of(new PostSwapRequest<RandomStringSecret>()).hex()).hasSize(64);
    }

    private static Proof<RandomStringSecret> proof(int amount, String secret, String signature) {
        RSSProof proof = new RSSProof();
        proof.setAmount(amount);
        proof.setKeySetId(KEYSET_ID);
        proof.setSecret(RandomStringSecret.fromString(secret));
        proof.setUnblindedSignature(Signature.fromString(signature));
        return proof;
    }

    private static List<BlindedMessage> outputs(String... blindedMessages) {
        List<BlindedMessage> outputs = new ArrayList<>();
        for (String blindedMessage : blindedMessages) {
            BlindedMessage output = new BlindedMessage();
            output.setAmount(8);
            output.setKeySetId(KeysetId.fromString(KEYSET_ID));
            output.setBlindedMessage(PublicKey.fromString(blindedMessage));
            outputs.add(output);
        }
        return outputs;
    }

    private static PostSwapRequest<RandomStringSecret> request(List<Proof<RandomStringSecret>> inputs,
                                                               List<BlindedMessage> outputs) {
        PostSwapRequest<RandomStringSecret> request = new PostSwapRequest<>();
        request.setInputs(inputs);
        request.setBlindedMessages(outputs);
        return request;
    }
}
