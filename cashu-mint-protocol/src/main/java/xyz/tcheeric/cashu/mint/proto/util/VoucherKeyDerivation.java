package xyz.tcheeric.cashu.mint.proto.util;

import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.cashu.common.PrivateKey;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

/**
 * Utility for deriving voucher signing keys for arbitrary amounts.
 *
 * <p>Unlike regular Cashu tokens that use power-of-2 denominations with pre-generated keys,
 * vouchers support arbitrary amounts. This utility derives a unique private key for any
 * amount using HMAC-SHA256 with the master secret and amount as inputs.
 *
 * <p>Key derivation formula: {@code key = HMAC-SHA256(masterSecret, "voucher:" + amount)}
 *
 * <p>This approach ensures:
 * <ul>
 *   <li>Deterministic key generation - same amount always produces same key</li>
 *   <li>No storage overhead - keys are derived on demand</li>
 *   <li>Security - derived keys cannot be guessed without the master secret</li>
 * </ul>
 */
@Slf4j
public final class VoucherKeyDerivation {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String KEY_PREFIX = "voucher:";

    private VoucherKeyDerivation() {
    }

    /**
     * Derive a private key for a voucher amount.
     *
     * @param masterSecret the keyset's master secret (32 bytes)
     * @param amount the voucher amount (can be any positive integer)
     * @return the derived private key for signing this amount
     * @throws IllegalArgumentException if masterSecret is null/empty or amount is non-positive
     * @throws RuntimeException if HMAC derivation fails
     */
    public static PrivateKey deriveKeyForAmount(byte[] masterSecret, long amount) {
        if (masterSecret == null || masterSecret.length == 0) {
            throw new IllegalArgumentException("Master secret cannot be null or empty");
        }
        if (amount <= 0) {
            log.warn("Invalid voucher amount requested: {}", amount);
            throw new IllegalArgumentException("Invalid voucher amount");
        }

        try {
            Mac hmac = Mac.getInstance(HMAC_ALGORITHM);
            SecretKeySpec secretKey = new SecretKeySpec(masterSecret, HMAC_ALGORITHM);
            hmac.init(secretKey);

            String derivationInput = KEY_PREFIX + amount;
            byte[] derivedKey = hmac.doFinal(derivationInput.getBytes(StandardCharsets.UTF_8));

            if (log.isDebugEnabled()) {
                log.debug("Derived voucher key for amount={}", amount);
            }

            return PrivateKey.fromBytes(derivedKey);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            log.error("Failed to derive voucher key for amount {}", amount, e);
            throw new RuntimeException("Key derivation failed", e);
        }
    }

    /**
     * Derive a private key for a voucher amount using a hex-encoded master secret.
     *
     * @param masterSecretHex the keyset's master secret as hex string (64 chars for 32 bytes)
     * @param amount the voucher amount
     * @return the derived private key for signing this amount
     */
    public static PrivateKey deriveKeyForAmount(String masterSecretHex, long amount) {
        if (masterSecretHex == null || masterSecretHex.isEmpty()) {
            throw new IllegalArgumentException("Master secret cannot be null or empty");
        }
        byte[] masterSecret = hexToBytes(masterSecretHex);
        return deriveKeyForAmount(masterSecret, amount);
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }
}
