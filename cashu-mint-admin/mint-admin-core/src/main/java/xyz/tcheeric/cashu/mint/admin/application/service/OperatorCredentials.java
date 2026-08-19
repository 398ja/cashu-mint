package xyz.tcheeric.cashu.mint.admin.application.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Issues and hashes operator credentials.
 *
 * <p>A credential is random and is shown to the caller exactly once, at issue
 * time. Only its SHA-256 hash is ever persisted, so a disclosure of the
 * operator table does not hand over working credentials.
 */
public final class OperatorCredentials {

    private static final int CREDENTIAL_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private OperatorCredentials() {
    }

    /**
     * Generate a new credential in its plaintext form.
     *
     * @return a fresh, random credential
     */
    public static String issue() {
        final byte[] bytes = new byte[CREDENTIAL_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Hash a credential for storage or lookup.
     *
     * @param credential plaintext credential
     * @return lowercase hex SHA-256 of the credential, or {@code null} when absent
     */
    public static String hash(final String credential) {
        if (credential == null || credential.isBlank()) {
            return null;
        }
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(credential.getBytes(StandardCharsets.UTF_8)));
        } catch (final NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required but unavailable", ex);
        }
    }
}
