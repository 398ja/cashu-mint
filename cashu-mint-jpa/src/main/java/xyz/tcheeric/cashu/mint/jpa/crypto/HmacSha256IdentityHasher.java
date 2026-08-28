package xyz.tcheeric.cashu.mint.jpa.crypto;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import xyz.tcheeric.cashu.mint.proto.ports.IdentityHasher;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Spec 004 FR-002 + Clarifications Q1 — default {@link IdentityHasher}
 * implementation. JDK-native HMAC-SHA-256 via {@link javax.crypto.Mac},
 * no third-party crypto dependency.
 *
 * <h2>Boot validation (fail-closed)</h2>
 * The configured salt MUST be non-null and at least 32 bytes (256 bits)
 * of entropy. {@link #validateSalt()} runs at {@code @PostConstruct}
 * and throws {@link IllegalStateException} on violation — the mint
 * refuses to serve voucher endpoints with a missing or weak salt.
 *
 * <h2>Thread safety</h2>
 * {@link Mac} is not thread-safe. This implementation uses a
 * {@link ThreadLocal} so each request thread reuses its own Mac
 * instance; HMAC-SHA-256 on a 32-byte input runs in ~5μs on JDK 21
 * with x86_64 SHA-NI, so this hot-path optimisation is comfortably
 * below the FR-012 / SC-006 p99 ≤ 1ms ceiling.
 *
 * <h2>Null short-circuit (FR-019)</h2>
 * Null / empty / whitespace input returns null — no enumerable
 * "hash of empty" placeholder is persisted. This is what makes
 * anonymous voucher purchases produce zero identity storage end-to-end.
 *
 * <p>Operator procedure — salt generation, rotation, retention — is in
 * {@code docs/runbooks/voucher-data-minimisation.md}.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "cashu.mint.jpa", name = "enabled", havingValue = "true")
public class HmacSha256IdentityHasher implements IdentityHasher {

    private static final String ALGORITHM = "HmacSHA256";
    private static final int MIN_SALT_BYTES = 32;

    private final byte[] saltBytes;
    private final ThreadLocal<Mac> macThreadLocal;

    public HmacSha256IdentityHasher(
            @Value("${cashu.mint.voucher.identity-salt:}") String identitySalt) {
        this.saltBytes = identitySalt == null
                ? new byte[0]
                : identitySalt.getBytes(StandardCharsets.UTF_8);
        // Cannot init the ThreadLocal until validateSalt() runs; defer Mac
        // creation to first use via a lazy supplier.
        this.macThreadLocal = ThreadLocal.withInitial(this::newInitialisedMac);
    }

    @PostConstruct
    void validateSalt() {
        if (saltBytes.length == 0) {
            throw new IllegalStateException(
                    "cashu.mint.voucher.identity-salt is required (set CASHU_MINT_VOUCHER_IDENTITY_SALT); "
                            + "see docs/runbooks/voucher-data-minimisation.md § 1 for the runbook");
        }
        if (saltBytes.length < MIN_SALT_BYTES) {
            throw new IllegalStateException(
                    "cashu.mint.voucher.identity-salt must be at least " + MIN_SALT_BYTES
                            + " bytes of entropy; got " + saltBytes.length
                            + " bytes. Generate via `openssl rand -hex 32`");
        }
        log.info("HmacSha256IdentityHasher wired (salt_bytes={})", saltBytes.length);
    }

    @Override
    public String hash(String value) {
        if (value == null || value.isBlank()) {
            // FR-019 — anonymous purchase short-circuit. No enumerable
            // hash-of-empty placeholder ever lands in the database.
            return null;
        }
        Mac mac = macThreadLocal.get();
        byte[] digest = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }

    private Mac newInitialisedMac() {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(saltBytes, ALGORITHM));
            return mac;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(ALGORITHM + " unavailable on this JDK", e);
        } catch (InvalidKeyException e) {
            throw new IllegalStateException("HmacSha256 init failed for the configured salt", e);
        }
    }
}
