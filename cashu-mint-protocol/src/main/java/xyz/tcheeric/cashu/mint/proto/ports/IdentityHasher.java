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
 * <p>The contract an implementation must honour:
 * <ul>
 *   <li>Deterministic — the same input always yields the same hash, because the
 *       operator forensic lookup depends on recomputing it.</li>
 *   <li>Null, empty and whitespace-only input return {@code null} rather than a
 *       hash, so an anonymous purchase stays anonymous instead of acquiring a
 *       fingerprint for the empty string.</li>
 *   <li>Output is 64-char lowercase hex.</li>
 *   <li>Thread-safe.</li>
 *   <li>Results are never cached: a cache is a memory-only re-identification
 *       channel, so every hash is recomputed from salt and input.</li>
 * </ul>
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
