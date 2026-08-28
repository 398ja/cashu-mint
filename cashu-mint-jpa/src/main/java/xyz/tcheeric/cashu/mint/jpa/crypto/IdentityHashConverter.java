package xyz.tcheeric.cashu.mint.jpa.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import xyz.tcheeric.cashu.mint.proto.ports.IdentityHasher;
import xyz.tcheeric.cashu.mint.proto.ports.MintIntegrityContext;

import java.util.regex.Pattern;

/**
 * Spec 004 T200 / FR-002 — JPA {@link AttributeConverter} that hashes
 * customer / merchant identity values on write. Applied via
 * {@link jakarta.persistence.Convert} on every identity-bearing column.
 *
 * <h2>Why the service-locator pattern</h2>
 * Hibernate instantiates {@code @Converter} classes outside the Spring
 * container — constructor injection doesn't work. The codebase already
 * uses {@link MintIntegrityContext} as a static service locator for
 * {@code MintQuoteRepository} / {@code VoucherFundingResolver} / etc.
 * (specs 001/002/003); we extend it with {@code identityHasher()} for
 * the same reason.
 *
 * <h2>Write-only conversion</h2>
 * {@link #convertToDatabaseColumn} hashes via {@link IdentityHasher}.
 * {@link #convertToEntityAttribute} is identity — the stored value IS
 * the hash, and we never reverse-resolve. Reading the column back
 * yields the same 64-char hex digest that was written.
 *
 * <h2>Null + legacy fallback</h2>
 * If the hasher is null (legacy unit-test context where the JPA module
 * isn't fully wired), the converter passes the value through unchanged
 * — same fallback shape spec 001/002/003 use. Production deploys MUST
 * have {@code cashu.mint.jpa.enabled=true} and a configured salt, both
 * fail-closed at boot (see {@link HmacSha256IdentityHasher}).
 *
 * <p>Hashing is one-way and deterministic; see {@link IdentityHasher} for the
 * full contract.
 */
@Converter
public class IdentityHashConverter implements AttributeConverter<String, String> {

    /** 64-char lowercase hex — the output shape of HmacSha256IdentityHasher. */
    private static final Pattern ALREADY_HASHED = Pattern.compile("^[0-9a-f]{64}$");

    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (attribute != null && ALREADY_HASHED.matcher(attribute).matches()) {
            // Spec 004 review fix (Copilot PR #324) — idempotency. A JPA
            // load followed by a merge / dirty-check would re-hash the
            // already-hashed value, breaking the forensic lookup
            // (which hashes the raw npub once). Skip when the value
            // already matches the stored hash format. The
            // VoucherIdentityBackfillService applies the same regex
            // for the same reason.
            return attribute;
        }
        IdentityHasher hasher = MintIntegrityContext.identityHasher();
        if (hasher == null) {
            // Legacy / unit-test path — no hasher wired, pass through.
            // Production fail-closed posture is enforced at boot, not
            // at converter call time.
            return attribute;
        }
        return hasher.hash(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        // The stored value IS the hash (or the original raw value in
        // legacy contexts); no reverse transform.
        return dbData;
    }
}
