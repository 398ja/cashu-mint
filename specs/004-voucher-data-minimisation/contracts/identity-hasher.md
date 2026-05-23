# Contract: `IdentityHasher` Port + HMAC-SHA-256 Implementation

**Feature**: 004-voucher-data-minimisation
**Phase**: 1
**Spec refs**: FR-002, FR-019, Clarifications Q1 + Q3
**Module**: port in `cashu-mint-protocol/.../proto/ports/`; default impl in `cashu-mint-jpa/.../jpa/crypto/`

## Port interface

```java
package xyz.tcheeric.cashu.mint.proto.ports;

/**
 * Spec 004 FR-002 — hash customer / merchant identifiers before
 * persisting. The default implementation is HMAC-SHA-256 with the
 * mint salt (Clarifications Q1). Null/empty inputs short-circuit to
 * null output so anonymous purchases (FR-019) leave no enumerable
 * hash-of-empty placeholder in the database.
 *
 * <p>The implementation is a Spring {@code @Component} in
 * {@code cashu-mint-jpa} so legacy unit-test contexts that don't
 * wire the JPA module get a no-op stub (preserves the spec 001/002
 * pattern where the JPA module is opt-in via
 * {@code cashu.mint.jpa.enabled=true}).
 */
public interface IdentityHasher {

    /**
     * Returns the salted hash of the given value, or {@code null} when
     * the input is null, empty, or whitespace-only. The hash is a
     * 64-character lowercase hex string when non-null.
     */
    String hash(String value);
}
```

## Default implementation contract

The default implementation in `cashu-mint-jpa/.../jpa/crypto/HmacSha256IdentityHasher.java` MUST:

1. **Use `javax.crypto.Mac.getInstance("HmacSHA256")`** — JDK-native, no third-party crypto dep.
2. **Source the salt from configuration property `cashu.mint.voucher.identity-salt`** — environment-variable-backed, never persisted, never logged.
3. **Reject startup if the salt is unset or < 32 bytes (256 bits) of entropy** — fail-closed in `@PostConstruct`; the mint MUST NOT serve voucher endpoints with a missing or weak salt.
4. **Short-circuit on null / empty / whitespace input** — return `null` without invoking the Mac. Anonymous purchase path (FR-019).
5. **Return 64-char lowercase hex** — `HexFormat.of().formatHex(mac.doFinal(value.getBytes(UTF_8)))`.
6. **Be thread-safe** — `Mac` is not, so the implementation either creates a fresh `Mac` per call (acceptable: ~5μs construction) OR uses `ThreadLocal<Mac>` for per-thread reuse. Either choice is documented inline.
7. **NOT cache hash results** — caching opens a memory-only re-identification channel; spec 004 specifically wants every hash recomputed from salt + input.

## Required behaviour table

| Input | Output | Notes |
|---|---|---|
| `null` | `null` | FR-019 — anonymous purchase |
| `""` | `null` | Whitespace-only same as null; prevents accidental empty-string fingerprint |
| `"   "` | `null` | Same |
| `"npub1abc…"` (real npub) | 64-char hex | The normal case |
| Same input twice | Same output | Deterministic — the operator forensic CLI relies on this |
| Different inputs | Different outputs (cryptographic) | SHA-256 collision resistance |
| Salt rotated | Different output for same input | Spec 004 v1 forbids salt rotation; this row documents the consequence |

## Performance contract

- p99 < 1ms (SC-006). Microbenchmark in `HmacSha256IdentityHasherBenchmarkTest` asserts on commodity x86_64 baseline.
- No allocations on the steady-state path beyond the Mac call + the returned `String`. Spring Boot's reflection + AOP overhead does NOT apply because `IdentityHasher` is a final concrete class wired by direct injection.

## Failure modes

| Condition | Behaviour |
|---|---|
| Salt unset at boot | `@PostConstruct` throws `IllegalStateException("cashu.mint.voucher.identity-salt is required")`; Spring context fails to start; mint refuses to serve traffic |
| Salt set but < 32 bytes | Same as above with `"... must be at least 256 bits of entropy"` |
| `Mac.getInstance("HmacSHA256")` throws (impossible on JDK 21) | Wrap in `IllegalStateException("HmacSHA256 unavailable")` |
| Caller passes a non-UTF-8-encodable value | Impossible — Java `String` is always UTF-encodable; the contract explicitly fixes UTF-8 |

## Test surface

| Test | Asserts |
|---|---|
| `HmacSha256IdentityHasherTest.hashesShortCircuitsOnNull` | Null in → null out, no Mac invocation |
| `HmacSha256IdentityHasherTest.hashesShortCircuitsOnEmpty` | Empty / whitespace in → null out |
| `HmacSha256IdentityHasherTest.hashesAreDeterministic` | Same input → same output across 1000 iterations |
| `HmacSha256IdentityHasherTest.hashesAre64CharHex` | Output matches `^[0-9a-f]{64}$` |
| `HmacSha256IdentityHasherTest.differentInputsProduceDifferentHashes` | 1000 random inputs → 1000 distinct outputs |
| `HmacSha256IdentityHasherTest.differentSaltsProduceDifferentHashes` | Verifies salt is actually keyed in |
| `HmacSha256IdentityHasherBootIT.failsOnMissingSalt` | Spring context fails to start when env var unset |
| `HmacSha256IdentityHasherBootIT.failsOnShortSalt` | Spring context fails to start when salt < 32 bytes |
| `HmacSha256IdentityHasherBenchmarkTest.p99UnderOneMs` | Per-call latency p99 < 1ms over 100k iterations |
