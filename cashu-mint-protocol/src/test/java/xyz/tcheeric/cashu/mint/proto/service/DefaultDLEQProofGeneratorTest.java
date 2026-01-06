package xyz.tcheeric.cashu.mint.proto.service;

import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec;
import org.bouncycastle.math.ec.ECPoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.common.DLEQProof;
import xyz.tcheeric.cashu.crypto.DLEQUtils;
import xyz.tcheeric.cashu.mint.proto.service.impl.DefaultDLEQProofGenerator;

import java.math.BigInteger;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultDLEQProofGeneratorTest {

    private static final ECNamedCurveParameterSpec CURVE = ECNamedCurveTable.getParameterSpec("secp256k1");

    @Test
    @DisplayName("Generates DLEQ proof that verifies against public key")
    // Validates that the generator produces a proof which passes DLEQ verification.
    void generatesProofThatVerifies() {
        BigInteger privateKey = new BigInteger(1, new byte[]{0x01, 0x02, 0x03, 0x04});
        ECPoint generator = CURVE.getG();
        ECPoint blindedMessage = generator.multiply(BigInteger.valueOf(7)).normalize();
        ECPoint blindSignature = blindedMessage.multiply(privateKey).normalize();
        ECPoint publicKey = generator.multiply(privateKey).normalize();

        DLEQProofGenerator generatorService = new DefaultDLEQProofGenerator();

        DLEQProof proof = generatorService.generateProof(privateKey, blindedMessage, blindSignature);

        boolean valid = DLEQUtils.verifyProof(
                proof.getE(),
                proof.getS(),
                blindedMessage,
                blindSignature,
                publicKey
        );

        assertThat(valid).isTrue();
    }
}
