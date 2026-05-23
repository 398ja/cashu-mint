package xyz.tcheeric.cashu.mint.proto.ports;

/**
 * Spec 004 FR-002 — hash customer / merchant identifiers before persisting.
 * The default implementation is HMAC-SHA-256 with the mint salt
 * (Clarifications Q1). Null / empty / whitespace-only inputs short-circuit
 * to null output so anonymous purchases (FR-019) leave no enumerable
 * hash-of-empty placeholder in the database.
 *
 * <p>Implemented by {@code HmacSha256IdentityHasher} in {@code cashu-mint-jpa}.
 * Legacy unit-test contexts that don't wire the JPA module get a no-op
 * stub (preserves the spec 001/002/003 pattern where the JPA module is
 * opt-in via {@code cashu.mint.jpa.enabled=true}).
 *
 * <p>Contract: see {@code specs/004-voucher-data-minimisation/contracts/identity-hasher.md}.
 *
 * @see <a href="../../../../../../../../specs/004-voucher-data-minimisation/spec.md">spec 004</a>
 */
public interface IdentityHasher {

    /**
     * Returns the salted hash of the given value, or {@code null} when the
     * input is null, empty, or whitespace-only. The hash is a 64-character
     * lowercase hex string when non-null. Deterministic — same input + same
     * salt always produces the same output (operator forensic lookups rely
     * on this).
     */
    String hash(String value);
}
