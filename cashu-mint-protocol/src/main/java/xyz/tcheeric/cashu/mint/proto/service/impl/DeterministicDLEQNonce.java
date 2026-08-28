package xyz.tcheeric.cashu.mint.proto.service.impl;

import lombok.NonNull;
import org.bouncycastle.math.ec.ECPoint;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.mint.proto.error.ErrorResponse;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

/**
 * NUT-12 deterministic DLEQ nonce derivation.
 *
 * <p>Derives {@code r = HMAC-SHA256(key=a, "Cashu_DLEQ_R_v1" || A || B' || C' || ctr)} with
 * rejection sampling on {@code ctr}, so a DLEQ proof never depends on the runtime RNG. Nonce
 * reuse across challenges leaks the mint private key outright, which is why the spec prefers
 * this construction over a random scalar.
 *
 * @see <a href="https://github.com/cashubtc/nuts/blob/main/12.md">NUT-12</a>
 */
final class DeterministicDLEQNonce {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private static final byte[] DOMAIN_SEPARATOR = "Cashu_DLEQ_R_v1".getBytes(StandardCharsets.US_ASCII);

    private static final int SCALAR_LENGTH = 32;

    private static final int MAXIMUM_COUNTER_ATTEMPTS = 256;

    private DeterministicDLEQNonce() {
    }

    /**
     * Derives the deterministic nonce for a DLEQ proof.
     *
     * @param privateKey     the mint private key {@code a}
     * @param publicKey      the mint public key {@code A}
     * @param blindedMessage the blinded message point {@code B'}
     * @param blindSignature the blind signature point {@code C'}
     * @param curveOrder     the secp256k1 curve order {@code n}
     * @return a nonce in {@code [1, n)}
     * @throws CashuErrorException if no counter value yields a usable nonce
     */
    static BigInteger derive(@NonNull BigInteger privateKey,
                             @NonNull ECPoint publicKey,
                             @NonNull ECPoint blindedMessage,
                             @NonNull ECPoint blindSignature,
                             @NonNull BigInteger curveOrder) throws CashuErrorException {
        byte[] message = nonceMessage(publicKey, blindedMessage, blindSignature);
        for (int counter = 0; counter < MAXIMUM_COUNTER_ATTEMPTS; counter++) {
            message[message.length - 1] = (byte) counter;
            BigInteger candidate = new BigInteger(1, hmacSha256(toScalarBytes(privateKey), message));
            if (candidate.signum() > 0 && candidate.compareTo(curveOrder) < 0) {
                return candidate;
            }
        }
        throw new CashuErrorException(
                new ErrorResponse("dleq_nonce_derivation_failed",
                        "No deterministic DLEQ nonce found within the NUT-12 counter range").toJson());
    }

    private static byte[] nonceMessage(ECPoint publicKey, ECPoint blindedMessage, ECPoint blindSignature) {
        byte[] a = publicKey.normalize().getEncoded(false);
        byte[] b = blindedMessage.normalize().getEncoded(false);
        byte[] c = blindSignature.normalize().getEncoded(false);
        byte[] message = new byte[DOMAIN_SEPARATOR.length + a.length + b.length + c.length + 1];
        int offset = 0;
        offset = append(message, DOMAIN_SEPARATOR, offset);
        offset = append(message, a, offset);
        offset = append(message, b, offset);
        append(message, c, offset);
        return message;
    }

    private static int append(byte[] target, byte[] source, int offset) {
        System.arraycopy(source, 0, target, offset, source.length);
        return offset + source.length;
    }

    private static byte[] toScalarBytes(BigInteger scalar) {
        byte[] unpadded = scalar.toByteArray();
        byte[] padded = new byte[SCALAR_LENGTH];
        if (unpadded.length > SCALAR_LENGTH) {
            System.arraycopy(unpadded, unpadded.length - SCALAR_LENGTH, padded, 0, SCALAR_LENGTH);
        } else {
            System.arraycopy(unpadded, 0, padded, SCALAR_LENGTH - unpadded.length, unpadded.length);
        }
        return padded;
    }

    private static byte[] hmacSha256(byte[] key, byte[] message) throws CashuErrorException {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
            return mac.doFinal(message);
        } catch (GeneralSecurityException e) {
            throw new CashuErrorException(
                    new ErrorResponse("dleq_nonce_derivation_failed", "HMAC-SHA256 unavailable").toJson());
        }
    }
}
