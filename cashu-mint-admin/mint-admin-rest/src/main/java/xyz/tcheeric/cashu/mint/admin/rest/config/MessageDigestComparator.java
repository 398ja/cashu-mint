package xyz.tcheeric.cashu.mint.admin.rest.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Utility for constant time token comparison to avoid trivial timing attacks.
 */
final class MessageDigestComparator {

    private MessageDigestComparator() {
    }

    static boolean equals(final String expected, final String provided) {
        final byte[] expectedBytes = expected == null ? new byte[0] : expected.getBytes(StandardCharsets.UTF_8);
        final byte[] providedBytes = provided == null ? new byte[0] : provided.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedBytes, providedBytes);
    }
}
