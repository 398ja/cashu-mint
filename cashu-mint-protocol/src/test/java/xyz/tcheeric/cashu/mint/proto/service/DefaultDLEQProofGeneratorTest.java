package xyz.tcheeric.cashu.mint.proto.service;

import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec;
import org.bouncycastle.math.ec.ECPoint;
import org.bouncycastle.util.encoders.Hex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.common.nut12.DLEQProof;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.crypto.DLEQUtils;
import xyz.tcheeric.cashu.mint.proto.service.impl.DefaultDLEQProofGenerator;

import java.math.BigInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultDLEQProofGeneratorTest {

    private static final ECNamedCurveParameterSpec CURVE = ECNamedCurveTable.getParameterSpec("secp256k1");

    private final DLEQProofGenerator generator = new DefaultDLEQProofGenerator();

    @Test
    @DisplayName("Generates DLEQ proof that verifies against public key")
    // Validates that the generator produces a proof which passes DLEQ verification.
    void generatesProofThatVerifies() throws CashuErrorException {
        BigInteger privateKey = new BigInteger(1, new byte[]{0x01, 0x02, 0x03, 0x04});
        ECPoint blindedMessage = CURVE.getG().multiply(BigInteger.valueOf(7)).normalize();
        ECPoint blindSignature = blindedMessage.multiply(privateKey).normalize();
        ECPoint publicKey = CURVE.getG().multiply(privateKey).normalize();

        DLEQProof proof = generator.generateProof(privateKey, blindedMessage, blindSignature);

        boolean valid = DLEQUtils.verifyProof(
                proof.getE(),
                proof.getS(),
                blindedMessage,
                blindSignature,
                publicKey
        );

        assertThat(valid).isTrue();
    }

    @Test
    @DisplayName("Reproduces the NUT-12 deterministic nonce test vector")
    // Checks the published NUT-12 vector: with a fixed key, B' and C', the mint must emit exactly this e and s.
    void reproducesTheDeterministicNonceVector() throws CashuErrorException {
        BigInteger privateKey = new BigInteger(
                "0000000000000000000000000000000000000000000000000000000000000002", 16);
        ECPoint blindedMessage = decode("02a9acc1e48c25eeeb9289b5031cc57da9fe72f3fe2861d264bdc074209b107ba2");
        ECPoint blindSignature = decode("0244eccfc7a348274458bb38044c7f3c389b3c2086c7ec18b5812d2877ab937787");

        DLEQProof proof = generator.generateProof(privateKey, blindedMessage, blindSignature);

        assertThat(proof.getE()).isEqualTo("2a16ffee280aff3c429045607f9b8e0bf8b35910c44c1b20b9dfaf01b263d7b3");
        assertThat(proof.getS()).isEqualTo("9df27731238334718d120d4f74611a7c668233f988e687ac3fb188f0a34a2dab");
    }

    @Test
    @DisplayName("Produces the same proof for the same inputs")
    // Confirms the nonce is deterministic rather than random, so repeated calls agree.
    void producesTheSameProofForTheSameInputs() throws CashuErrorException {
        BigInteger privateKey = BigInteger.valueOf(9_999);
        ECPoint blindedMessage = CURVE.getG().multiply(BigInteger.valueOf(11)).normalize();
        ECPoint blindSignature = blindedMessage.multiply(privateKey).normalize();

        DLEQProof first = generator.generateProof(privateKey, blindedMessage, blindSignature);
        DLEQProof second = generator.generateProof(privateKey, blindedMessage, blindSignature);

        assertThat(first.getE()).isEqualTo(second.getE());
        assertThat(first.getS()).isEqualTo(second.getS());
    }

    @Test
    @DisplayName("Rejects a private key outside the curve order")
    // Ensures an out-of-range key fails loudly instead of yielding an unusable proof.
    void rejectsPrivateKeyOutsideCurveOrder() {
        ECPoint point = CURVE.getG().normalize();

        assertThatThrownBy(() -> generator.generateProof(CURVE.getN(), point, point))
                .isInstanceOf(CashuErrorException.class)
                .extracting(t -> ((CashuErrorException) t).getErrorCode().name()).isEqualTo("internal_error");
    }

    private static ECPoint decode(String compressedHex) {
        return CURVE.getCurve().decodePoint(Hex.decode(compressedHex)).normalize();
    }
}
