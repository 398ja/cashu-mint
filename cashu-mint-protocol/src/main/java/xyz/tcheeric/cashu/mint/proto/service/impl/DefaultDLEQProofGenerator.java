package xyz.tcheeric.cashu.mint.proto.service.impl;

import lombok.NonNull;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec;
import org.bouncycastle.math.ec.ECPoint;
import xyz.tcheeric.cashu.common.nut12.DLEQProof;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.rest.ErrorResponse;
import xyz.tcheeric.cashu.mint.proto.service.DLEQProofGenerator;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Default NUT-12 DLEQ proof generator using a deterministic nonce.
 *
 * <p>Computes {@code e = hash(R1, R2, A, C')} and {@code s = (r + e*a) mod n}, where {@code r}
 * comes from {@link DeterministicDLEQNonce} rather than the RNG, as NUT-12 recommends.
 */
public class DefaultDLEQProofGenerator implements DLEQProofGenerator {

    private static final ECNamedCurveParameterSpec CURVE = ECNamedCurveTable.getParameterSpec("secp256k1");

    private static final int SCALAR_HEX_LENGTH = 64;

    @Override
    public DLEQProof generateProof(
            @NonNull BigInteger privateKey,
            @NonNull ECPoint blindedMessage,
            @NonNull ECPoint blindSignature
    ) throws CashuErrorException {
        BigInteger curveOrder = CURVE.getN();
        requireScalarInRange(privateKey, curveOrder);

        ECPoint publicKey = CURVE.getG().multiply(privateKey).normalize();
        BigInteger nonce = DeterministicDLEQNonce.derive(
                privateKey, publicKey, blindedMessage, blindSignature, curveOrder);

        ECPoint nonceTimesGenerator = CURVE.getG().multiply(nonce).normalize();
        ECPoint nonceTimesBlindedMessage = blindedMessage.multiply(nonce).normalize();

        BigInteger challenge = new BigInteger(1, hashChallenge(
                nonceTimesGenerator, nonceTimesBlindedMessage, publicKey, blindSignature)).mod(curveOrder);
        BigInteger response = nonce.add(challenge.multiply(privateKey)).mod(curveOrder);

        return DLEQProof.forBlindSignature(toScalarHex(challenge), toScalarHex(response));
    }

    private static void requireScalarInRange(BigInteger privateKey, BigInteger curveOrder)
            throws CashuErrorException {
        if (privateKey.signum() <= 0 || privateKey.compareTo(curveOrder) >= 0) {
            throw new CashuErrorException(
                    new ErrorResponse("dleq_private_key_out_of_range",
                            "DLEQ proof requires a private key in [1, n)").toJson());
        }
    }

    /**
     * NUT-12 {@code hash_e}: SHA-256 over the concatenated uncompressed hex of each point.
     */
    private static byte[] hashChallenge(ECPoint... points) throws CashuErrorException {
        StringBuilder concatenated = new StringBuilder();
        for (ECPoint point : points) {
            concatenated.append(toUncompressedHex(point));
        }
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(concatenated.toString().getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new CashuErrorException(
                    new ErrorResponse("dleq_generation_failed", "SHA-256 unavailable").toJson());
        }
    }

    private static String toUncompressedHex(ECPoint point) {
        return toHex(point.normalize().getEncoded(false));
    }

    private static String toScalarHex(BigInteger scalar) {
        String hex = scalar.toString(16);
        return "0".repeat(Math.max(0, SCALAR_HEX_LENGTH - hex.length())) + hex;
    }

    private static String toHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(String.format("%02x", b & 0xFF));
        }
        return hex.toString();
    }
}
