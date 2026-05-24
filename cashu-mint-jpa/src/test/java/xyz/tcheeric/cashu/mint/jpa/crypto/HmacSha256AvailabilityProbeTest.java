package xyz.tcheeric.cashu.mint.jpa.crypto;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Spec 004 T002 — smoke probe that {@code HmacSHA256} is available on the
 * project's JDK runtime. Spec 004 FR-002 requires HMAC-SHA-256 for identity
 * hashing via {@code javax.crypto.Mac}, and the rest of the implementation
 * depends on this primitive existing without a third-party crypto dep.
 *
 * <p>This test exists to fail fast on a JDK that lacks the algorithm rather
 * than discover it inside the production hash path.
 */
class HmacSha256AvailabilityProbeTest {

    @Test
    void hmacSha256IsAvailableOnThisJdk() {
        assertThatNoException().isThrownBy(() -> Mac.getInstance("HmacSHA256"));
    }

    @Test
    void hmacSha256ProducesA32ByteOutput() throws NoSuchAlgorithmException, InvalidKeyException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("spec-004-probe-salt".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] output = mac.doFinal("npub1probe".getBytes(StandardCharsets.UTF_8));
        assertThat(output).hasSize(32);
    }
}
