# Application Security Review: Cashu Ecosystem (September 2026)

A secure code review of the Cashu Java repositories, performed in the role of
Application Security Engineer. It covers the cryptographic core, the mint's
protocol and REST surfaces, the key/proof vault, the trace ledger, the voucher
domain, and the wallet client, plus the CI security controls that guard them.

Scope, method, and the reasoning behind each finding are stated so the report can
be re-run and its conclusions re-checked rather than taken on trust.

## Scope

| Repository | Java files | What was reviewed |
|---|---|---|
| `cashu-lib` | 234 | BDHKE, BIP-340 Schnorr, DLEQ (NUT-12), secret encodings, key types, REST DTOs |
| `cashu-mint` | 639 | Swap/mint/melt tasks, NUT-11 spending conditions, Spring Security, rate limiting, webhook HMAC, JPA/JDBC layer, admin module |
| `cashu-vault` | 66 | Bearer-token authentication, proof/keyset persistence, HashiCorp backend |
| `cashu-ledger` | 275 | Trace API, NIP-98 authentication, redaction-key admin surface |
| `cashu-voucher` | 66 | Issuer signature canonicalisation and verification |
| `cashu-wallet` | 80 | DLEQ verification policy, recovery services |

Method: manual review of security-critical paths (value creation, value
destruction, authentication, authorization, cryptographic operations, request
parsing), cross-referenced against the NUT specifications and the OWASP Top 10 /
CWE Top 25. Existing audit remediation notes in the code were treated as claims to
verify, not as evidence.

## Executive summary

The codebase is in unusually good security health for its size. The value-critical
invariants are not just present but centralised so that they cannot be forgotten:
BDHKE authenticity is checked once per input in `VerifyProofsTask` rather than
delegated to each spending condition, the swap takes a durable exclusive hold on its
inputs before signing anything, NUT-11 thresholds count distinct public keys rather
than signatures, the DLEQ nonce is HMAC-derived rather than random, and MAC and
token comparisons are constant-time. Several classes carry comments naming the exact
exploit their structure prevents, which is the strongest sign that prior findings
were fixed by design change rather than by patching a symptom.

Two genuine gaps remain, both of the same kind: a limit that is declared in one
layer and never enforced in the layer that receives the request.

| Sev | ID | Finding | Location |
|---|---|---|---|
| High | H-1 | Bean-validation size limits on `/v1/restore` and `/v1/checkstate` are never enforced; no `@Valid`, no validation starter — **fixed in this review** | `CashuController`, `cashu-mint-rest/pom.xml` |
| Medium | M-1 | Trace ledger authorization is per-controller convention, not a filter-chain rule (`anyRequest().permitAll()`); 19 request mappings across 5 controllers have no authorization call at all | `cashu-ledger-web/.../SecurityConfig` |
| Medium | M-2 | Issuance rate-limit identity is client-supplied and spoofable; the only real boundary is deployment topology | `IssuanceRateLimitFilter` |
| Medium | M-3 | Admin authentication is a single shared in-memory credential, plain-text by default | `cashu-mint-rest/.../SecurityConfig` |
| Low | L-1 | Jackson has no `StreamReadConstraints`; deep/long JSON is bounded only by the 2 MiB body cap | mint REST |
| Low | L-2 | Identity backfill interpolates table and column names into SQL | `VoucherIdentityBackfillBatch` |
| Low | L-3 | Legacy secret encoding is enabled by default, widening the set of secrets that verify | `SecretEncoding` |
| Low | L-4 | `CRITICAL`-only CI gate; HIGH findings never block a merge | `dependency-scan.yml` |
| Info | I-1 | CORS defaults to any origin with a startup warning | mint REST |

---

## H-1: Declared request-size limits are not enforced (CWE-770) — FIXED

**Status: remediated in this review.** The fix, the regression tests, and the evidence that
those tests detect the vulnerability are all described below.

**Where.** `cashu-mint-rest/.../controller/CashuController.java:475,480`

The DTOs carry exactly the right constraints:

```java
// cashu-lib-entities/.../nut09/PostRestoreRequest.java
public static final int MAX_OUTPUTS = 1000;

@JsonProperty("outputs")
@NotNull @NotEmpty
@Size(max = MAX_OUTPUTS, message = "Maximum " + MAX_OUTPUTS + " outputs allowed")
@Valid
private List<BlindedMessage> blindedMessages;
```

`PostCheckStateRequest` declares `MAX_SECRETS = 1000` the same way. Neither is ever
applied. The controller methods bind the body without `@Valid`:

```java
@PostMapping("/checkstate")
public ResponseEntity<PostCheckStateResponse> checkstate(@RequestBody PostCheckStateRequest request)

@PostMapping("/restore")
public ResponseEntity<PostRestoreResponse> restore(@RequestBody PostRestoreRequest request)
```

Two independent reasons the constraints cannot fire:

1. No `@Valid` annotation anywhere in `cashu-mint-rest/src/main`
   (`grep -rn "@Valid\|jakarta.validation" cashu-mint-rest/src/main` returns nothing).
2. No `spring-boot-starter-validation` dependency in `cashu-mint-rest/pom.xml`, so no
   `LocalValidatorFactoryBean` exists to enforce them even if `@Valid` were added.

**Impact.** Both endpoints are unauthenticated and both loop per element:
`RestoreSignaturesTask` issues one `signatureVaultService.retrieve(bm)` call per
output, and `CrossMintCheckStateMerger` multiplies each requested `Y` by the number
of mints (active *and* archived). A single 2 MiB request holds roughly 10,000-20,000
entries, each becoming a vault round trip. With virtual threads and
`max-connections=2000`, a modest number of concurrent requests amplifies into
hundreds of thousands of vault calls: an unauthenticated amplification DoS against
the vault, which is the component the swap and melt paths depend on. Note that the
`SecurityLimits.MAX_PROOFS` / `MAX_BLINDED_MESSAGES` checks that protect swap and
mint have no counterpart on these two paths.

**This is the gap in an otherwise sound defence.** `server.tomcat.max-http-form-post-size`
and `spring.servlet.multipart.max-request-size` do not bound a
`Content-Type: application/json` body. The 2 MiB ceiling that does apply comes from
`max-swallow-size`, which bounds bytes, not element count, and 2 MiB of elements is
20x the intended limit.

**Fix applied.** Both halves are required; either alone leaves the limit inert.

```xml
<!-- cashu-mint-rest/pom.xml -->
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-validation</artifactId>
</dependency>
```

```java
@PostMapping("/checkstate")
public ResponseEntity<PostCheckStateResponse> checkstate(
        @Valid @RequestBody PostCheckStateRequest request) throws CashuErrorException {
    return ResponseEntity.ok(checkStateMerger.merge(request));
}

@PostMapping("/restore")
public ResponseEntity<PostRestoreResponse> restore(
        @Valid @RequestBody PostRestoreRequest request) throws CashuErrorException {
    return ResponseEntity.ok(NUT09.restore(request, signatureVaultService));
}
```

Then map `MethodArgumentNotValidException` in the existing `@ExceptionHandler` block
to a NUT error code, so a rejected request returns the protocol's error shape rather
than Spring's default body.

`MethodArgumentNotValidException` is now mapped in the existing `@ExceptionHandler` block,
so a rejected request returns the protocol's `ErrorResponse` shape rather than Spring's
default body.

**Defence in depth applied.** The limit is also enforced inside the tasks, mirroring how
`SwapTask` checks its own counts, so a future controller that forgets `@Valid` is still
bounded. `RestoreSignaturesTask.requireCountWithinLimit` raises `too_many_outputs`;
`CheckStateTask.requireCountWithinLimit` raises `too_many_inputs`. Enforcing at the task
layer also means the limit holds for every caller of `NUT07.checkState` and
`NUT09.restore`, not only the HTTP path.

**Regression tests and their evidence.** Two tests assert the rejection *and* that the vault
was never consulted (`verifyNoInteractions`), because a 4xx returned after the vault work is
done would not fix the amplification:

- `RestoreSignaturesTaskTest.refusesMoreOutputsThanTheMaximumWithoutTouchingTheVault`
- `CheckStateTaskTest.refusesMoreSecretsThanTheMaximumWithoutTouchingTheVault`

To confirm these test the vulnerability rather than restating the implementation, the two
`requireCountWithinLimit` calls were commented out and the suite re-run. Both tests failed
(`Expected CashuErrorException to be thrown, but nothing was thrown`), and both passed again
once the calls were restored. A test that cannot fail is not evidence.

---

## M-1: Trace ledger authorization depends on convention, not configuration

**Where.** `cashu-ledger-web/.../config/SecurityConfig.java:43`

```java
http.csrf(csrf -> csrf.disable())
        .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
        .addFilterBefore(nip98Filter, UsernamePasswordAuthenticationFilter.class);
```

The NIP-98 filter populates a principal, but the filter chain authorizes nothing.
Every access decision is an in-method `requireAdmin(request)` call. Those calls are
present today (`TraceAdminController` 9 occurrences, `TraceController` 20), but the
other five controllers (`ProxyController`, `VoucherController`, `WatchController`,
`TraceStreamController`, `UnclaimedController`) have none. Some of those are
legitimately public reads; the problem is that nothing in the configuration says
which, so a new admin endpoint is unauthenticated by default and a deleted
`requireAdmin` line is a silent privilege escalation rather than a build failure.

Counted per controller (authorization calls vs. request mappings):

| Controller | Authz calls | Mappings |
|---|---|---|
| `TraceController` | 20 | 12 |
| `TraceAdminController` | 9 | 5 |
| `ProxyController` | 0 | 7 |
| `VoucherController` | 0 | 6 |
| `UnclaimedController` | 0 | 3 |
| `HomeController` | 0 | 1 |
| `TraceStreamController` | 0 | 1 |
| `WatchController` | 0 | 1 |

So 19 mappings across five controllers are reachable with no authorization check in the
handler and no rule in the chain.

To be fair to the current state: these look like intentionally public reads. `ProxyController`
despite its name is not an open forwarder — it delegates to the same local
`VoucherLedgerService` as `/api/v1/vouchers/*`, serving `/proxy/*` for frontend compatibility,
and voucher inspection is a public capability by design. So this finding is about the *absence
of a stated boundary*, not a known-exploitable hole. The risk is the next endpoint, not the
current ones: with `anyRequest().permitAll()`, an admin route added to any of these classes is
open until someone remembers the in-method call, and no test or configuration will say
otherwise. That is why the recommendation is to encode the decision in the chain rather than
to add checks to these five controllers.

This is the same structural lesson `ProofAuthenticity` documents in the mint: "every
handler remembers to do this" is the pattern that already failed once.

**Fix.** Make the chain deny by default and enumerate the public reads:

```java
.authorizeHttpRequests(auth -> auth
        .requestMatchers("/api/v1/trace/admin/**").hasAuthority("trace:admin")
        .requestMatchers(HttpMethod.GET, "/api/v1/vouchers/**").permitAll()
        .requestMatchers(EndpointRequest.to("health")).permitAll()
        .anyRequest().authenticated())
```

Keep the in-method checks as defence in depth. Add a test that asserts an
unauthenticated call to each admin route returns 401.

---

## M-2: Issuance rate-limit identity is attacker-controlled

**Where.** `cashu-mint-rest/.../ratelimit/IssuanceRateLimitFilter.java:85-92`

The class documents this honestly, which is why it is Medium rather than High:
identity is the client-supplied header when present, and the remote-address fallback
applies only when the header is absent. A caller who can reach `/v1/mint` directly
rotates the header per request and mints a fresh bucket every time. The control is
therefore worth exactly as much as the network boundary in front of it.

Two hardening steps that do not wait for NUT-22 blind auth:

1. **Always bind to the remote address, and treat the header as a sub-key.** Rotating
   the header then subdivides one IP's quota instead of escaping it:
   ```java
   private String resolveIdentity(HttpServletRequest request) {
       String ip = request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
       String header = request.getHeader(properties.getIdentityHeader());
       return header == null || header.isBlank()
               ? "ip:" + ip
               : "ip:" + ip + "|id:" + sanitizeIdentity(header);
   }
   ```
2. **Only trust the header from a trusted peer.** Add a configurable trusted-proxy CIDR
   list and ignore the header from anywhere else, failing closed when unset.

Also note the filter is in-process and Caffeine-backed, so the effective limit
multiplies by replica count. Worth stating in the deployment runbook before the mint
goes multi-replica.

---

## M-3: Admin authentication is one shared, plain-text-by-default credential

**Where.** `cashu-mint-rest/.../config/SecurityConfig.java`

The fail-closed behaviour is correct: a blank password registers zero users, so
`/admin/**` returns 401 with no guessable fallback, and the earlier source-visible
sentinel password was rightly reverted. Remaining weaknesses:

- `{noop}` is the default encoding, so the credential is plain text in configuration
  unless the operator knows to run `spring encodepassword`. The warning log helps but
  does not change the default.
- One credential for all operators means no attribution and no per-operator rotation.
  Contrast `mint-admin-rest`, which does this properly: NAP Nostr sessions, a
  `@RequiresPermission` interceptor, and an `OperatorIdentity` that refuses to record
  a claimed identity in the audit trail.
- `/v1/vouchers/**` requires the same `ADMIN` role as mint administration, so the
  merchant-facing voucher API and the operational control plane share one credential.
  That is a blast-radius problem: a leaked merchant integration credential is a mint
  admin credential.

**Recommendations.** Refuse to start in a non-local profile when the password lacks an
encoder prefix. Introduce a distinct merchant principal for `/v1/vouchers/**`. Longer
term, front the public mint's `/admin/**` with the same NAP session mechanism the
admin module already uses, which removes the shared secret entirely.

---

## Low-severity findings

**L-1: No Jackson `StreamReadConstraints`.** Deeply nested or very long JSON strings
are bounded only by the 2 MiB body cap. Jackson 2.18 defaults are reasonable, but the
limits should be explicit and tighter than the defaults for a protocol whose messages
are shallow and fixed-shape:

```java
@Bean
Jackson2ObjectMapperBuilderCustomizer streamReadConstraints() {
    return builder -> builder.postConfigurer(mapper -> mapper.getFactory()
            .setStreamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(20)
                    .maxStringLength(100_000)
                    .maxNumberLength(1_000)
                    .build()));
}
```

**L-2: SQL identifier interpolation in the identity backfill.**
`VoucherIdentityBackfillBatch.hashOneBatch` builds `SELECT DISTINCT <column> FROM
<liveTable>` by concatenation. Not currently exploitable: the only production caller
passes keys from the static `LIVE_TABLES` map, and the values are parameterised. It is
still a public method with a string table name, one careless caller away from
injection. Validate against an allowlist at the method boundary:

```java
private static final Set<String> ALLOWED_TABLES =
        Set.of("voucher_quote", "voucher_quote_aud", "customer_payment_funding", /* ... */);

private static String requireAllowedIdentifier(String identifier, Set<String> allowed) {
    if (!allowed.contains(identifier)) {
        throw new IllegalArgumentException("Not a permitted identifier: " + identifier);
    }
    return identifier;
}
```

**L-3: Legacy secret encoding on by default.** `SecretEncoding.LEGACY_ENABLED`
defaults to true, so verification tries two encodings and two distinct byte strings
map to two valid `Y` values per proof. The trade-off is documented and correct while
pre-migration proofs circulate, and `SpentProofKey.lookupKeys` correctly checks the
double-spend table under both keys, so this does not enable a double spend. It does
widen the accepted set. Recommendation: track outstanding pre-migration proofs as a
metric, publish a sunset date, and flip the default to false at the next major
version so the compatibility path is opt-in.

**L-4: CI gate blocks only on CRITICAL.** The reasoning in `dependency-scan.yml` for
not blocking on HIGH is sound (alert fatigue trains everyone to ignore the check).
For a mint that custodies value, split the difference: keep HIGH non-blocking for
transitive dependencies, but block on HIGH in the cryptographic and web-facing direct
dependency set (BouncyCastle, Jackson, Spring Security, Tomcat, Netty). Also add
secret scanning (gitleaks or `trufflehog`) — no repository currently has it, and the
dependency scan does not cover credentials. `cashu-ledger` and `cashu-voucher` are
missing scans that the other four repositories have; `cashu-ledger` has no
`dependency-scan.yml` at all.

**I-1: CORS defaults to any origin.** Defensible for a public NUT surface and warned
about at startup. Make it an explicit decision by failing startup in non-local
profiles when `cashu.mint.cors.allowed-origins` is unset.

---

## What is done well, and worth keeping

These are load-bearing and should be protected by tests before any refactor:

- **BDHKE authenticity is centralised.** `VerifyProofsTask` calls
  `ProofAuthenticity.require(proof)` for every input before dispatching to a spending
  condition. The class comment records the exploit this structure prevents: when the
  check lived inside each condition, `P2PKSpendingCondition` did not have it, so a
  secret locked to an attacker's key with arbitrary bytes in `C` bought real signed
  outputs. Unlimited issuance with no deposit. A new spending condition can now add
  requirements but cannot drop this one.
- **Swap ordering is a proper saga.** `SwapProofHold` claims all inputs atomically
  (`state = 'UNSPENT' AND hold_id IS NULL`), records `SIGNING` *before* the first
  signature so that a crash either side of the write is read conservatively, then
  commits or strands. `release()` is never called after signing. A commit that spends
  fewer proofs than were held is a failure, not a success, which catches a no-op vault.
  The failure mode is a loss for one wallet, never inflation.
- **NUT-11 thresholds count distinct keys.** `SigningKeyCounter` deduplicates on the
  x-only coordinate, so the same signature submitted twice, or a repeated pubkey in a
  crafted secret, cannot satisfy an n-of-m threshold.
- **Locktime is a `long`.** The comment records the narrowing bug: an `int` locktime
  past 2038 wrapped negative, read as "long expired", and unlocked the proof.
- **DLEQ nonce is HMAC-derived.** `DeterministicDLEQNonce` removes RNG dependence
  from proof generation; nonce reuse across challenges leaks the mint private key
  outright, so this is the right construction.
- **DLEQ scalars are bounded before point multiplication.** `requireScalar` rejects
  anything over 32 bytes rather than reducing it, closing both a CPU-DoS and the
  "reduce an oversized scalar and accept a proof the signer never made" hazard.
- **Constant-time comparisons throughout.** `MessageDigest.isEqual` over decoded
  bytes in the webhook validator, and over SHA-256 digests in the vault's
  `BearerTokenAuthenticationFilter` so the comparison cost does not vary with the
  guess length.
- **Webhook replay is bounded.** The timestamp is inside the signed payload, so
  editing the header invalidates the MAC, and `require-timestamp` defaults to true.
- **Actuator is on a separate port, enforced at startup.** `ManagementPortGuard` fails
  boot if the management port equals the API port, so the separation cannot be undone
  by configuration. This matters: `/actuator/prometheus` exposes issuance rates and
  outstanding liability.
- **Secrets have no packaged defaults.** The voucher identity salt, webhook secret,
  trace publisher key, and NUT-06 identity fields all fail closed or omit themselves
  rather than shipping a value identical across every deployment. No hardcoded
  credentials were found outside test resources.
- **`PrivateKey` is `final`, `@JsonIgnoreType`, and `AutoCloseable`** with honest
  documentation of what JVM zeroing cannot guarantee.
- **Mint suspension preserves exit.** Suspension refuses issuance only; swap and melt
  stay available, so holders can always redeem. That is the correct direction for a
  custodial kill switch.

## Recommended remediation order

| Priority | Action | SLA |
|---|---|---|
| 1 | H-1: add validation starter + `@Valid`, plus in-task limits and regression tests | **Done** |
| 2 | M-1: deny-by-default ledger filter chain | 30 days |
| 3 | M-2: bind rate-limit identity to remote address; trusted-proxy allowlist | 30 days |
| 4 | M-3: refuse plain-text admin password in non-local profiles; split merchant principal | 30 days |
| 5 | L-1, L-2, L-4: stream constraints, identifier allowlist, secret scanning, ledger dependency scan | 90 days |
| 6 | L-3: legacy-encoding sunset plan with an outstanding-proof metric | 90 days |

## Suggested standing controls

- **Security regression tests as policy.** Each finding above should close with a test
  that fails on the unfixed code. H-1's two tests are the template.
- **An `@Valid` lint.** The root cause of H-1 is that a constraint can be declared in
  one module and silently ignored in another. An ArchUnit rule requiring every
  `@RequestBody` parameter whose type declares Bean Validation constraints to also
  carry `@Valid` would have caught it at build time, and would catch the next one.
- **Threat model per NUT.** New NUT implementations touch value creation or
  destruction by definition. A short STRIDE pass at design time, producing testable
  requirements, is cheaper than the `ProofAuthenticity` class of discovery.

---

*Reviewed against the NUT specifications at cashubtc/nuts. Findings are based on
static review of the source at the September 2026 `master` of each repository; no
dynamic testing or deployed-environment assessment was performed.*
