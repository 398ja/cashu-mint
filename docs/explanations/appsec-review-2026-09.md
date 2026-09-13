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

**A scope correction.** Those six are the repositories that were checked out locally. Auditing
the scope afterwards against `gh repo list 398ja` found three more: `cashu-papers` and
`cashu-projects` (documentation only, no dependencies, 0 alerts once enabled), and
`cashu-platform-bom` — a real Maven BOM managing dependency versions for the ecosystem, with no
CI scanning, Dependabot disabled, and `postgresql` pinned at the same vulnerable 42.7.7. A Bill
of Materials is the last repository a dependency review should skip, since its entire job is
deciding which versions everything else gets. Filed as
[cashu-platform-bom#2](https://github.com/398ja/cashu-platform-bom/issues/2); rated Medium
rather than High because nothing currently imports it (`cashu-wallet` copied its plugin
versions by hand rather than importing the artifact).

`cashu-client` and `cashu-gateway` exist as local directories but contain no code and no git
repository; `cashu-voucher.hide` is a superseded scaffold of `cashu-voucher`. `cashu-mint-admin`
is archived, and its code lives inside `cashu-mint` where it was reviewed.

Method: manual review of security-critical paths (value creation, value
destruction, authentication, authorization, cryptographic operations, request
parsing), cross-referenced against the NUT specifications and the OWASP Top 10 /
CWE Top 25. Existing audit remediation notes in the code were treated as claims to
verify, not as evidence — and two of them did not survive that.

Every finding that could be executed was executed rather than inspected, because the
inspection-only passes in this review produced three wrong claims (H-1's second cause, M-1's
endpoint counts, and the locktime rationale below). Concretely, that means: Jackson's real
`StreamReadConstraints.defaults()` were printed, not recalled; `SecretEncoding.verificationOrder()`
was run to confirm the legacy encoding is live; the DLEQ scalar bound was invoked with an
oversized value to confirm it rejects rather than reduces; `SecurityConfig.adminUserDetails`
was called with blank, plain and `{bcrypt}` passwords to observe what it registers; the CI
scanner matrix was built by listing each repository's workflows; and the NUT-11 distinct-key
rule was covered with a new test using real Schnorr signatures, then mutation-checked by
deleting the deduplication and watching one key satisfy a 2-of-2.

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

Two findings matter. **H-1** (fixed here) is a pair of size limits declared in one layer and
never applied in the layer that receives the request. **L-4**, upgraded to High after
measurement, is that the dependency scan reports zero findings on every Maven module while
Dependabot reports 45 alerts on the same tree. The rest are hardening items.

Those two and **M-4** share one shape, which is the most useful thing this review produced: a
control that is configured, reports success, and is not connected to anything. A limit that is
declared but never applied; a test suite that is present but never run; a scanner that runs but
detects nothing. Each looked healthy from the outside.

Two findings in this report were **downgraded after re-measurement**, and both corrections are
recorded in place rather than quietly edited out. H-1 was reported as having two independent
causes; it had one. M-1 was reported as Medium with 19 unguarded endpoints; re-counting showed
the measurement was wrong and the trace surface fail-closes, so it is now Low. The method that
caught both was the same: run the mutation, do not reason about it.

| Sev | ID | Finding | Location |
|---|---|---|---|
| High | H-1 | Bean-validation size limits on `/v1/restore` and `/v1/checkstate` are never enforced: the controllers bind the body without `@Valid` — **fixed in this review** | `CashuController` |
| Low | M-1 | Trace ledger authorization lives in a filter prefix and per-handler throws, not in the filter chain (`anyRequest().permitAll()`); fail-closed today, but the boundary is not stated where it is configured | `cashu-ledger-web/.../SecurityConfig` |
| Medium | M-2 | Issuance rate-limit identity is client-supplied and spoofable; the only real boundary is deployment topology | `IssuanceRateLimitFilter` |
| Medium | M-3 | Admin authentication is a single shared in-memory credential, plain-text by default | `cashu-mint-rest/.../SecurityConfig` |
| Low | L-1 | Jackson has no `StreamReadConstraints`; deep/long JSON is bounded only by the 2 MiB body cap | mint REST |
| Low | L-2 | Identity backfill interpolates table and column names into SQL | `VoucherIdentityBackfillBatch` |
| Low | L-3 | Legacy secret encoding is enabled by default, widening the set of secrets that verify | `SecretEncoding` |
| High | L-4 | The dependency scan reports zero findings on every Maven module while Dependabot reports 45 alerts (1 critical, 20 high) on the same tree — a control that is configured but not connected | `dependency-scan.yml` |
| Info | I-1 | CORS defaults to any origin with a startup warning | mint REST |
| Medium | M-4 | Integration tests are skipped by default and not run by CI; 5 are currently failing on `master` unnoticed | `pom.xml`, `.github/workflows/ci.yml` |

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

The reason the constraints cannot fire: no `@Valid` annotation anywhere in
`cashu-mint-rest/src/main` (`grep -rn "@Valid\|jakarta.validation" cashu-mint-rest/src/main`
returned nothing). Spring only validates a `@RequestBody` when the parameter asks to be
validated, so the constraints were declared and never consulted.

**A correction to an earlier draft of this finding.** This report first claimed a *second*
independent cause — that `cashu-mint-rest` declared no validation starter, so no validator
existed either way. That was wrong, and the way it was caught is worth recording. The first
regression test built its MockMvc with `standaloneSetup(...).setValidator(...)`, supplying its
own validator, so it could not distinguish the two hypotheses. Re-testing through the real
application context, then removing the starter from the pom and re-running, showed the
assertions still passing: `mvn dependency:tree` traces a validator into the module
transitively through `cashu-mint-jpa`. So the vulnerability had one cause, not two, and the
missing `@Valid` was sufficient on its own to disable both limits.

The explicit starter dependency is kept regardless, because relying on a runtime-scoped
persistence module to supply a web-layer validator is how this would quietly regress: if
`cashu-mint-jpa` drops it, these constraints go silent with nothing failing to compile.

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

**Fix applied.** The `@Valid` annotations are the operative change; the starter is declared so
the validator is a stated dependency rather than an inherited accident.

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

**Web-layer verification, at two levels.** The task tests do not exercise the HTTP contract, so
the web layer is covered twice, deliberately:

- `CashuControllerRequestLimitTest` (`cashu-mint-rest`) drives Spring MVC's argument resolver
  with an explicitly supplied `LocalValidatorFactoryBean`. Three assertions, including that a
  request *at* the limit still returns 200, without which a fix that rejected everything would
  pass.
- `RequestSizeLimitIT` (`cashu-mint-rest-it`) asserts the same through the real application
  context, where the only validator is the one Spring Boot auto-configures. This is the one
  that tests the production wiring; the unit test supplies its own validator and therefore
  cannot.

Note that the integration tests are skipped unless `-Pintegration-tests` is active, so a plain
`mvn verify` does not run `RequestSizeLimitIT`. It was run explicitly with that profile.

**Two things the mutation testing corrected.** Removing `@Valid` while keeping the task checks
turned the *checkstate* assertion from 400 into 200 while restore still passed. The cause is
structural and worth recording: `CrossMintCheckStateMerger` only reaches `CheckStateTask` once
it has a mint to query, so with no mints the task-layer check never runs and the list is
unbounded across mint loading itself. The two layers guard overlapping but not identical paths.

Removing the *starter* changed nothing, which is what exposed the false second root cause
described above. Both results came from running the mutation, not from reasoning about it.

---

## M-1 (downgraded to Low): the trace boundary is not stated in the filter chain

**Where.** `cashu-ledger-web/.../config/SecurityConfig.java:43`

```java
http.csrf(csrf -> csrf.disable())
        .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
        .addFilterBefore(nip98Filter, UsernamePasswordAuthenticationFilter.class);
```

The chain authorizes nothing, so every access decision is made elsewhere. The question is
whether "elsewhere" is reliable, and on inspection it largely is — this finding is weaker than
its first draft claimed, and the correction is recorded below.

**How access is actually decided.** `Nip98AuthenticationFilter` fail-closes for the whole trace
surface. Its `shouldNotFilter` matches on the prefix `/api/v1/trace`, and within that prefix an
unauthenticated request gets 401 (`TRACE_UNAUTHENTICATED`) and a request whose pubkey resolves
to no authority gets 403 (`TRACE_FORBIDDEN`). Neither reaches a controller. Only then is a
`TracePrincipal` attached as a request attribute.

On top of that:

- `TraceAdminController` (`/api/v1/trace/admin`) calls `requireAdmin(request)`, which throws
  `AdminForbiddenException` unless the principal `isAdmin()`. Six occurrences across five
  mappings.
- `TraceController` (`/api/v1/trace`) reads the principal to decide the *response shape* —
  redaction — rather than to gate entry, and its `principal(request)` helper throws
  `IllegalStateException` if the attribute is absent. So it fails closed too, by a different
  mechanism.
- The remaining controllers sit outside the trace prefix entirely: `/proxy` (7 mappings),
  `/api/v1/vouchers` (6), `/api/v1/unclaimed` (3), `/api/v1/watch` (1), and `HomeController`
  (1). These are the public voucher-inspection reads the class comment describes as
  intentionally open, and `ProxyController` despite its name is not a forwarder: it delegates to
  the same local `VoucherLedgerService` as `/api/v1/vouchers/*`.

**A correction.** An earlier draft of this finding reported "19 request mappings across 5
controllers with no authorization call," including `TraceController` with "20 authorization
calls." Both numbers were artefacts of a bad measurement: `grep -c` counts matching *lines*
rather than occurrences, and the pattern included the type name `TracePrincipal`, so
`TraceController`'s redaction logic was miscounted as access control. Re-counting occurrences
with `grep -o | wc -l` gives `TraceController` zero `requireAdmin` calls — which prompted
reading it properly and finding the filter that actually does the work.

**What remains, at reduced severity.** The residual issue is narrow but real: the chain says
`permitAll()`, so the boundary lives in a filter's `shouldNotFilter` prefix and in per-handler
throws rather than in the authorization configuration. Two consequences worth fixing:

1. An admin route added under a *different* prefix — or the trace prefix ever being renamed —
   is unauthenticated with nothing failing to compile or to test.
2. `TraceController`'s protection depends on an `IllegalStateException` from a helper. That is
   fail-closed, but it presents as a 500 rather than a 401, so the failure mode is an alert
   rather than a clean rejection.

Encoding the decision in the chain makes both explicit:

```java
.authorizeHttpRequests(auth -> auth
        .requestMatchers("/api/v1/trace/admin/**").hasAuthority("trace:admin")
        .requestMatchers("/api/v1/trace/**").authenticated()
        .requestMatchers(HttpMethod.GET, "/api/v1/vouchers/**", "/proxy/**",
                "/api/v1/unclaimed/**", "/api/v1/watch/**").permitAll()
        .requestMatchers(EndpointRequest.to("health")).permitAll()
        .anyRequest().authenticated())
```

Keep the filter and the in-handler checks as defence in depth. Add a test asserting 401 on each
admin route without credentials, so the boundary is stated in one place and verified in another.

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

## M-4: the integration suite is not run, and is currently red

**Where.** `pom.xml:37` (`<skip.integration-tests>true</skip.integration-tests>`),
`.github/workflows/ci.yml`

Integration tests only execute under `-Pintegration-tests`, and no CI workflow passes that
flag — `grep -n "integration-tests" .github/workflows/ci.yml` returns nothing. So `mvn verify`,
which is what the contributing guide asks for and what CI runs, reports success without
executing a single IT.

Running `mvn -Pintegration-tests verify` on an unmodified `master` (verified by stashing all
local changes first) fails with 5 errors in `cashu-mint-protocol`:

- `MintThenRestoreIntegrationTest.mintThenRestore`
- 4 of 5 in `NUT13RecoveryIntegrationTest`

All fail with `CashuErrorException: Private key not found`, i.e. keyset fixture setup rather
than a protocol defect. That they are *fixable* is not the point: they cover NUT-09 restore and
NUT-13 recovery, which is exactly the surface H-1 was found on, and nobody has seen them fail
because nothing runs them.

**Why this is a security finding and not just hygiene.** A skipped suite is indistinguishable
from a passing one in every report anyone reads. `ActuatorManagementPortIT` — the test that
keeps `/actuator/prometheus` off the public port, protecting issuance rates and outstanding
liability — is in that same unrun set. Its careful javadoc explains precisely how it would
regress. It has not run in CI.

**Recommendation.** Add an `integration-tests` job to CI, even if it starts as non-blocking
while the 5 failures are repaired, so the trend is visible. Then fix the fixtures and make it
blocking. This finding was discovered only because verifying H-1's fix required running the IT
profile by hand.

---

## Low-severity findings

**L-1: No Jackson `StreamReadConstraints`.** Deeply nested or very long JSON strings
are bounded only by the 2 MiB body cap. The defaults were measured rather than assumed —
running `StreamReadConstraints.defaults()` against the resolved `jackson-core` 2.18.3 gives
`maxNestingDepth=1000`, `maxStringLength=20000000` (20 MB) and `maxNumberLength=1000`.

Two of those three are larger than the request body the mint will accept, so in the current
configuration they can never trigger: the 2 MiB cap is the real limit and Jackson's own
guards are inert. That makes this genuinely low severity — it is not an open door, it is a
guard positioned behind a narrower one, so it offers no defence if the body cap is ever
raised or bypassed. Setting limits that match the protocol's actual shape (shallow,
fixed-form messages) is what makes them load-bearing:

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

**L-4 (upgraded to High after measurement): the dependency scan detects nothing on the Java side.**

This finding originally argued about blocking thresholds. That framing was wrong, and the
correction came from an accident: pushing a branch printed GitHub's warning that the default
branch carries **45 Dependabot alerts (1 critical, 20 high, 20 moderate, 4 low)** — on a
repository whose Trivy `scan` job is green.

Checking the CI log for run 34768961641, the per-file table sums to **zero vulnerabilities
across every `pom.xml`**, and zero on `package-lock.json`. Dependabot, on the same tree:

| Severity | Package | Pinned | Vulnerable range | Fixed in |
|---|---|---|---|---|
| high | `jackson-databind` | 2.18.1 | `>= 2.10.0, < 2.18.8` | 2.18.8 |
| high | `postgresql` | 42.7.7 | `>= 42.2.0, < 42.7.11` | 42.7.11 |
| high | `postgresql` | 42.7.7 | `>= 42.7.4, < 42.7.12` | 42.7.12 |
| critical | `vitest` (npm, dev scope) | — | `< 3.2.6` | 3.2.6 |

`jackson.version` and `postgresql.version` are set in the parent `pom.xml` at lines 75 and 90 —
direct, deliberate pins, not obscure transitives.

**Why it misses them.** The first explanation written here was that Trivy cannot resolve
property-managed versions. That was wrong, and checking it produced a more specific and more
actionable answer: **the coordinate and its version never appear in the same file.**

The parent `pom.xml` carries them under `<dependencyManagement>`, which declares what version to
use *if* something depends on it, rather than declaring a dependency:

```xml
<dependency>
  <groupId>com.fasterxml.jackson.core</groupId>
  <artifactId>jackson-databind</artifactId>
  <version>${jackson.version}</version>   <!-- 2.18.1 -->
</dependency>
```

The module that actually depends on it, `cashu-mint-jpa/pom.xml`, declares it with no version:

```xml
<dependency>
  <groupId>com.fasterxml.jackson.core</groupId>
  <artifactId>jackson-databind</artifactId>
</dependency>
```

So a file-based scan sees, per file, either a managed version that is not a dependency or a
dependency with no version. Neither is a resolvable "package X at version Y" to match against an
advisory. The join happens inside Maven, in memory. The `dependency:resolve` step before the
scan populates `~/.m2` but emits nothing Trivy reads, so the workflow comment — "Trivy reads the
resolved dependency tree, not just the poms" — describes an intent the configuration does not
achieve.

This matters for the remedy: no flag or alternate file path helps, because no file in the tree
contains the joined fact. The scan must consume something post-resolution.

The mechanism was then checked against a second repository rather than generalised from one.
`cashu-lib` has the identical split: `assertj.version` (3.27.4, against an open high advisory
fixed in 3.27.7) sits in the parent's `<dependencyManagement>`, while `cashu-lib-crypto` and
`cashu-lib-common` declare `assertj-core` with no version. Same shape, same blind spot, same
green scan over four open alerts. This is the ordinary multi-module Maven layout, so the gap
should be assumed everywhere in the ecosystem that uses it rather than re-derived each time.

Advisory staleness was ruled out: the five Maven HIGHs were published between 2026-05-05 and
2026-07-21, alerts were raised 2026-07-26, and the scan ran 2026-09-13.

**Ecosystem-wide, measured:**

| Repository | Trivy scan | Open Dependabot alerts |
|---|---|---|
| `cashu-mint` | green | **45** (1 critical, 20 high) |
| `cashu-lib` | green | **4** (`assertj-core` high, `jackson-databind` medium) |
| `cashu-ledger` | **no scan at all** | **6** (1 critical, 1 high) — *after enabling alerts* |
| `cashu-voucher` | green | 0 |
| `cashu-wallet` | green | 0 |
| `cashu-vault` | green | 0 — *after enabling alerts* |
| `cashu-platform-bom` | **no scan at all** | **2** (both high, `postgresql`) — *after enabling alerts* |

`cashu-vault` and `cashu-ledger` had Dependabot alerts **disabled**, so they had no independent
signal at all and would have looked identical whether clean or not. Enabling alerts is
reversible, additive and touches no code, so it was done during the review rather than filed:
`cashu-vault` turned out to be genuinely clean, while `cashu-ledger` surfaced six findings
immediately — on the one repository that also has no dependency scan.

Its CRITICAL is a false alarm, which is worth stating because an unexplained CRITICAL trains
people to ignore the list. CVE-2026-33634 affects `aquasecurity/trivy-action < 0.35.0`;
`cashu-ledger` references trivy-action nowhere, and every sibling pins `@v0.36.0`. The real
finding there is a runtime `logback-core` cluster clearing at 1.5.34.

**This is the review's third instance of one pattern.** H-1 was a limit declared and never
applied. M-4 is a test suite configured and never run. L-4 is a scanner wired up and detecting
nothing. In all three the control exists, reports success, and is load-bearing in someone's
mental model. That pattern — not any individual finding — is the most useful output of this
review.

**Recommendations:**

1. Scan resolved coordinates rather than poms: generate a CycloneDX SBOM
   (`cyclonedx-maven-plugin`) and point `scan-type: sbom` at it, or run `trivy rootfs` over the
   resolved `~/.m2` artifacts. Adding flags to the existing `scan-type: fs` step cannot work —
   the joined coordinate-plus-version fact exists in no file.
2. Add a CI assertion that the scan detects a known-vulnerable fixture, so a
   silently-detecting-nothing scanner fails loudly. A check that can only pass is not a check.
3. Enable Dependabot alerts on `cashu-vault` and `cashu-ledger`; add a scan to `cashu-ledger`.
4. Add secret scanning (`gitleaks` or `trufflehog`) — no repository has it. An entropy-based
   sweep during this review found only a deliberate Playwright fixture key whose private key is
   literally `1`, so there is nothing to clean up today; that is luck holding, not a control.
5. Bump `jackson-databind` to 2.18.8 and `postgresql` to 42.7.12 — both patch bumps within the
   pinned minor — independently of how the tooling question is settled.
6. Revisit the CRITICAL-vs-HIGH threshold *after* detection works. The original reasoning
   against blocking on HIGH (alert fatigue) is sound, and remains a reasonable debate to have
   once there is something to be fatigued by.

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

  **But nothing asserts the ordering.** Inverting it to `signOutputs()` then
  `markSigning()` — precisely the hazard the javadoc warns about — leaves all 377
  `cashu-mint-protocol` tests green. No test in that module so much as references
  `markSigning`. The reasoning is documented and correct; a refactor that tidied those two
  lines into a more natural-looking order would pass CI and reopen a double-spend window.
  Tracked as [cashu-mint#437](https://github.com/398ja/cashu-mint/issues/437).
- **NUT-11 thresholds count distinct keys.** `SigningKeyCounter` deduplicates on the
  x-only coordinate, so the same signature submitted twice, or a repeated pubkey in a
  crafted secret, cannot satisfy an n-of-m threshold. Verified with real BIP-340 signatures
  in `SigCountTest`, added by this review: one key signing twice counts once, two distinct
  keys count twice. Deleting the deduplication makes a single key satisfy a 2-of-2, i.e.
  forge a multisig — so the test detects the thing that matters. It had **no prior test
  coverage**, which for a rule standing between one key and an n-of-m spend is worth fixing
  regardless of the implementation being correct.
- **Locktime is a `long`.** `P2PKSecret.getLockTime()` returns `long`, confirmed by
  reflection against the built class. This is the right type and the fix is real.

  One correction to how the fix is described in the code, which this report repeated
  uncritically before checking it. The comment in `P2PKSpendingCondition` says a narrowed
  `int` locktime past 2038 "wrapped negative, which this comparison then read as 'long
  expired' and unlocked the proof". Working through the historical code (`git log -S`, commit
  `980ecb0a`) shows the guard was always `lockTime > 0 && lockTime < now`, and a wrapped
  value is negative, so it fails the `> 0` test and the locktime reads as *not* passed. The
  actual pre-fix failure was therefore the opposite one: a proof with a post-2038 locktime
  stayed locked forever, and the refund pathway — which is only reachable after the locktime
  passes — became unreachable. Funds stuck rather than funds stealable.

  That is a milder bug than the comment claims, though still worth having fixed, and the
  `long` type is correct either way. The point of recording it: a code comment asserting a
  security rationale is a claim like any other, and this one does not survive reading the
  history it refers to.
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

## Tracking

Every finding is filed as a GitHub issue labelled `app-sec-report`, in the repository that owns
the code. Severity labels (`sev:high` … `sev:info`) match the table above.

| Finding | Issue |
|---|---|
| H-1 | [cashu-mint#424](https://github.com/398ja/cashu-mint/issues/424) — fixed in [#433](https://github.com/398ja/cashu-mint/pull/433) |
| M-1 | [cashu-ledger#8](https://github.com/398ja/cashu-ledger/issues/8) |
| M-2 | [cashu-mint#425](https://github.com/398ja/cashu-mint/issues/425) |
| M-3 | [cashu-mint#426](https://github.com/398ja/cashu-mint/issues/426) |
| M-4 | [cashu-mint#427](https://github.com/398ja/cashu-mint/issues/427) |
| L-1 | [cashu-mint#428](https://github.com/398ja/cashu-mint/issues/428) |
| L-2 | [cashu-mint#429](https://github.com/398ja/cashu-mint/issues/429) |
| L-3 | [cashu-lib#264](https://github.com/398ja/cashu-lib/issues/264) |
| L-4 (High) | [cashu-mint#432](https://github.com/398ja/cashu-mint/issues/432), [cashu-lib#266](https://github.com/398ja/cashu-lib/issues/266), [cashu-vault#140](https://github.com/398ja/cashu-vault/issues/140), [cashu-voucher#36](https://github.com/398ja/cashu-voucher/issues/36), [cashu-wallet#48](https://github.com/398ja/cashu-wallet/issues/48), [cashu-ledger#9](https://github.com/398ja/cashu-ledger/issues/9) |
| I-1 | [cashu-mint#430](https://github.com/398ja/cashu-mint/issues/430) |

Items outside the findings table, surfaced while verifying other claims or promised in
§ Suggested standing controls:

| Item | Issue |
|---|---|
| NUT-11 distinct-key counting had no test coverage | [cashu-lib#265](https://github.com/398ja/cashu-lib/issues/265) |
| The locktime narrowing rationale is inverted in the code comment | [cashu-mint#431](https://github.com/398ja/cashu-mint/issues/431) |
| `CONTRIBUTING.md` targets a `develop` branch 143 commits stale | [cashu-mint#434](https://github.com/398ja/cashu-mint/issues/434) |
| ArchUnit rule requiring `@Valid` on constrained `@RequestBody` parameters | [cashu-mint#435](https://github.com/398ja/cashu-mint/issues/435) |
| Bump `jackson` 2.18.1 → 2.18.8 and `postgresql` 42.7.7 → 42.7.12 (5 runtime HIGHs) | [cashu-mint#436](https://github.com/398ja/cashu-mint/issues/436) |
| Audit load-bearing invariants for test coverage, not just correctness | [cashu-mint#437](https://github.com/398ja/cashu-mint/issues/437) |
| `cashu-platform-bom` was outside the reviewed scope: no scanning, alerts off, vulnerable pins | [cashu-platform-bom#2](https://github.com/398ja/cashu-platform-bom/issues/2) |

---

## Recommended remediation order

| Priority | Action | SLA |
|---|---|---|
| 1 | H-1: `@Valid` on both endpoints, in-task limits, regression tests at unit and integration level | **Done** |
| 2 | L-4: make the dependency scan detect resolved coordinates; bump `jackson-databind` to 2.18.8 and `postgresql` to 42.7.12; enable Dependabot where disabled | 7 days |
| 3 | M-4: run the integration suite in CI; repair the 5 pre-existing failures | 30 days |
| 4 | M-2: bind rate-limit identity to remote address; trusted-proxy allowlist | 30 days |
| 5 | M-3: refuse plain-text admin password in non-local profiles; split merchant principal | 30 days |
| 6 | L-4 (cont.): secret scanning across all repositories; dependency scan for `cashu-ledger` | 90 days |
| 7 | L-1, L-2: stream constraints, identifier allowlist | 90 days |
| 8 | L-3: legacy-encoding sunset plan with an outstanding-proof metric | 90 days |
| 9 | M-1: deny-by-default ledger filter chain (Low; fail-closed today) | 90 days |

## Suggested standing controls

- **Security regression tests as policy.** Each finding above should close with a test
  that fails on the unfixed code. H-1's tests and `SigCountTest` are the template.
- **Cover the invariants, not just the fixes.** `SigningKeyCounter` was correct but untested,
  and so is the swap-hold `markSigning` ordering — inverting it leaves all 377 protocol tests
  green. A property that no test asserts is a property the next refactor may remove silently,
  and "the comment explains why" is not a control. Tracked as
  [cashu-mint#437](https://github.com/398ja/cashu-mint/issues/437), with a list of the
  invariants worth mutating.
- **An `@Valid` lint.** The root cause of H-1 is that a constraint can be declared in
  one module and silently ignored in another. An ArchUnit rule requiring every
  `@RequestBody` parameter whose type declares Bean Validation constraints to also
  carry `@Valid` would have caught it at build time, and would catch the next one.
- **Threat model per NUT.** New NUT implementations touch value creation or
  destruction by definition. A short STRIDE pass at design time, producing testable
  requirements, is cheaper than the `ProofAuthenticity` class of discovery.

---

*Reviewed against the NUT specifications at cashubtc/nuts, at the September 2026 `master` of
each repository. Findings in `cashu-mint` were checked by execution — building, running the
suites, and mutating the fix to confirm the tests detect its absence. Findings in the other five
repositories are static review only. No deployed-environment or network assessment was
performed, so the deployment-topology assumptions behind M-2 are taken on the code's word.*

*Two findings were downgraded during the review after re-measurement showed the first reading
was wrong, and M-4 was added only because verifying H-1 required running a test profile that CI
does not. Both are recorded rather than tidied away: a review that never corrects itself is
reporting its own confidence, not the codebase.*
