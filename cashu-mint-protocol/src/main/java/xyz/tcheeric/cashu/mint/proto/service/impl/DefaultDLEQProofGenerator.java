package xyz.tcheeric.cashu.mint.proto.service.impl;

import lombok.NonNull;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.math.ec.ECCurve;
import org.bouncycastle.math.ec.ECMultiplier;
import org.bouncycastle.math.ec.ECPoint;
import org.bouncycastle.math.ec.FixedPointCombMultiplier;
import xyz.tcheeric.cashu.common.nut12.DLEQProof;
import xyz.tcheeric.cashu.common.nut00.CashuErrorCode;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.mint.proto.service.DLEQProofGenerator;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default NUT-12 DLEQ proof generator using a deterministic nonce.
 *
 * <p>Computes {@code e = hash(R1, R2, A, C')} and {@code s = (r + e*a) mod n}, where {@code r}
 * comes from {@link DeterministicDLEQNonce} rather than the RNG, as NUT-12 recommends.
 */
public class DefaultDLEQProofGenerator implements DLEQProofGenerator {

    // BouncyCastle's specialised secp256k1 (GLV endomorphism, fast field) instead of the generic
    // named-curve table. Points are imported into it, so arithmetic never mixes curve instances.
    private static final X9ECParameters CURVE = CustomNamedCurves.getByName("secp256k1");

    private static final ECCurve FAST_CURVE = CURVE.getCurve();

    private static final ECMultiplier BASE_MULTIPLIER = new FixedPointCombMultiplier();

    /** a*G per mint private key; a mint has a handful of keyset keys, so the cap is never hit. */
    private static final int PUBLIC_KEY_CACHE_LIMIT = 1024;

    private static final ConcurrentHashMap<BigInteger, ECPoint> PUBLIC_KEYS = new ConcurrentHashMap<>();

    private static final byte[] HEX_DIGITS = "0123456789abcdef".getBytes(StandardCharsets.US_ASCII);

    private static final int SCALAR_HEX_LENGTH = 64;

    @Override
    public DLEQProof generateProof(
            @NonNull BigInteger privateKey,
            @NonNull ECPoint blindedMessage,
            @NonNull ECPoint blindSignature
    ) throws CashuErrorException {
        BigInteger curveOrder = CURVE.getN();
        requireScalarInRange(privateKey, curveOrder);

        ECPoint publicKey = publicKeyFor(privateKey);
        blindedMessage = FAST_CURVE.importPoint(blindedMessage);
        blindSignature = FAST_CURVE.importPoint(blindSignature);
        BigInteger nonce = DeterministicDLEQNonce.derive(
                privateKey, publicKey, blindedMessage, blindSignature, curveOrder);

        ECPoint nonceTimesGenerator = BASE_MULTIPLIER.multiply(CURVE.getG(), nonce).normalize();
        ECPoint nonceTimesBlindedMessage = blindedMessage.multiply(nonce).normalize();

        BigInteger challenge = new BigInteger(1, hashChallenge(
                nonceTimesGenerator, nonceTimesBlindedMessage, publicKey, blindSignature)).mod(curveOrder);
        BigInteger response = nonce.add(challenge.multiply(privateKey)).mod(curveOrder);

        return DLEQProof.forBlindSignature(toScalarHex(challenge), toScalarHex(response));
    }

    private static ECPoint publicKeyFor(BigInteger privateKey) {
        ECPoint cached = PUBLIC_KEYS.get(privateKey);
        if (cached != null) {
            return cached;
        }
        ECPoint computed = BASE_MULTIPLIER.multiply(CURVE.getG(), privateKey).normalize();
        if (PUBLIC_KEYS.size() < PUBLIC_KEY_CACHE_LIMIT) {
            PUBLIC_KEYS.putIfAbsent(privateKey, computed);
        }
        return computed;
    }

    private static void requireScalarInRange(BigInteger privateKey, BigInteger curveOrder)
            throws CashuErrorException {
        if (privateKey.signum() <= 0 || privateKey.compareTo(curveOrder) >= 0) {
            throw new CashuErrorException(CashuErrorCode.internal_error,
                            "DLEQ proof requires a private key in [1, n)");
        }
    }

    /**
     * NUT-12 {@code hash_e}: SHA-256 over the concatenated uncompressed hex of each point.
     */
    private static byte[] hashChallenge(ECPoint... points) throws CashuErrorException {
        byte[] hex = new byte[points.length * 130];
        int offset = 0;
        for (ECPoint point : points) {
            offset = appendHex(hex, offset, point.normalize().getEncoded(false));
        }
        try {
            return MessageDigest.getInstance("SHA-256").digest(hex);
        } catch (NoSuchAlgorithmException e) {
            throw new CashuErrorException(CashuErrorCode.internal_error, "SHA-256 unavailable");
        }
    }

    private static int appendHex(byte[] target, int offset, byte[] bytes) {
        for (byte b : bytes) {
            target[offset++] = HEX_DIGITS[(b >> 4) & 0x0F];
            target[offset++] = HEX_DIGITS[b & 0x0F];
        }
        return offset;
    }

    private static String toScalarHex(BigInteger scalar) {
        String hex = scalar.toString(16);
        return "0".repeat(Math.max(0, SCALAR_HEX_LENGTH - hex.length())) + hex;
    }

}
