# Changelog

All notable changes to the Cashu Mint will be documented in this file.

## [Unreleased]

### Added

- **An interoperability test drives an external Cashu implementation through
  mint, swap and melt against this mint** (audit finding M10, Milestone 0).
  `NutshellInteropIT` starts the reference Python implementation
  (`cashubtc/nutshell:0.16.5`) in a container and has it transact against a mint
  booted in the same JVM, with the dummy Lightning adapter and a scripted payment
  port standing in for a node. Published NUT vectors cannot settle whether our
  ecash is spendable elsewhere; only a foreign implementation can. It fails today
  and that is the point — it is the instrument, not the result. Two findings it
  already produced: the mint refuses any output split but the canonical minimal
  one, which no wallet chooses; and Nutshell's proofs fail our swap verification,
  the first external evidence for the `hash_to_curve` secret-encoding question
  (L1) that Milestone 1 has to answer. Without a Docker daemon it fails loudly
  rather than skipping, so a broken environment cannot hide a regression. See
  [Run the interoperability test](docs/how-to/run-the-interoperability-test.md).

### Documentation

- The NUT compliance audit gains an implementation plan: eight milestones, each
  ending at a releasable state, with all 21 findings assigned exactly once (18
  scheduled, 3 deferred). The ordering is driven by two constraints found in the
  code rather than by severity — `cashu-mint` consumes `cashu-lib` as a published
  artifact, so library work gates mint work; and the swap path has no
  transactional boundary, which makes the sign-before-validate fix a reordering
  rather than a rollback. Establishing the test vectors and an interoperability
  harness comes first, because the `hash_to_curve` encoding question cannot be
  settled without one and every later milestone changes crypto or wire format.

## [0.32.0] - 2026-08-28

### Added

- **An Operator can see a mint's keysets, so a rotation can be confirmed without
  reaching for `psql`.** `GET /admin/lifecycle/mints/{mintId}/keysets` answers
  what the shared vault holds — keyset id, unit, created-at, and whether the mint
  signs with it or has archived it — with the signing keyset first and archived
  ones newest-first, ordered server-side so the rule is stated once. The admin
  renders it at `/mints/:mintId/keysets`, reached from the mint detail page. A
  vault read that fails is a `502`, never an empty list: reporting the one as the
  other would tell an Operator that a rotation destroyed key material that is in
  fact still there. The archived badge reads "Archived · still redeems", because
  an Operator opens this page after a rotation afraid the old keyset went away.
  Key material is dropped in the vault adapter, so no layer above it holds a
  private key it could log or serialise. (#381, #382)

- Each keyset expands into its denominations, ascending, each carrying the vault
  path its key lives at. An Operator taking a backup needs to reach the private
  keys, and the admin cannot hand one over: the vault stores none in its own
  database, keys live in HashiCorp Vault behind credentials the admin does not
  hold, and asking the mint over HTTP is closed by ADR-0003. Where each key lives
  is what a backup actually needs. Archived keysets answer too, since past key
  material is precisely what a recovery wants (ADR-0004).

- `MINT_ADMIN` can run the operational controls. Operations are reachable only
  from a mint's detail page, which needs `mint:lifecycle`, so `OPS_ADMIN` alone
  could not get there and a `MINT_ADMIN` looking at a stuck mint needed a second
  role to act on it. The ACL test now reads permissions off the enum rather than
  restating them, so the two cannot drift apart again.

- Enrolling a browser key asks for the passphrase twice. A typo encrypts the key
  under a passphrase nobody knows, and the key lives only in that browser, so the
  confirmation happens before the save rather than on the next visit.

### Changed

- **The dev and E2E stacks run a vault-backed mint, so the admin and the mint are
  one system.** The mint served keysets from `preload-test-data.json` because
  `PreloadMintLoadService` is `@Primary` and on by default, and neither compose
  file disabled it. A mint the admin provisioned, and every rotation of its
  keyset, therefore landed in the vault and was invisible at `/v1/keysets`. Both
  stacks now set `MINT_PRELOAD_ENABLED=false` and point the mint at HashiCorp.
  `KeyRotationE2EIT` is no longer `@Disabled`: it asserts a rotation against the
  mint rather than against the admin's own echo.

- Seeding the dev keyset is separate from serving it. `VaultPreloadSeeder` seeds
  the vault at startup from the same JSON, so disabling preload no longer takes
  the bootstrap data with it. The `vault-db-seed` compose service is removed: it
  loaded SQL inserting `t_key.private_key`, a column dropped when key material
  moved to HashiCorp, so every run failed and seeded nothing. Key material cannot
  be seeded over SQL, because only the backend-aware vault knows where the secret
  goes.

- The operational controls listing reports `controlType` and `outcome`. Without
  them an operator could see that a rotation completed but not which keyset
  replaced which. The admin UI showed a permanently blank "Reason" column bound
  to a field the API never sent; it now shows the type and the outcome.

### Fixed

- **Provisioning no longer gives a unit a second active keyset.** A mint whose
  keyset was established by something other than the create-mint saga would get a
  second one, leaving two keysets claiming to sign for the unit; the next
  rotation then archived both and could not say which it replaced. An existing
  active keyset now stands.

- Key rotation no longer fails on every attempt. The vault permits a mint one
  *active* keyset per unit, so provisioning the replacement before archiving the
  keyset it replaces was rejected outright and every rotation ended
  `KEY_ROTATION_FAILED`. A rotation now archives its predecessor first and
  reinstates it if the replacement cannot be provisioned, so a failure part-way
  never leaves the mint unable to sign. A mint holding no keysets is treated as
  nothing to supersede rather than as a vault failure.

- The dev stack no longer re-creates the constraint that makes rotation
  impossible. A `vault-db-init` service applied `V1__init_schema.sql` with `psql`
  on every `up`, restoring the `UNIQUE (unit, mint_id)` index that migration `V5`
  drops precisely so a rotated-away keyset can coexist with its replacement. The
  vault schema is owned by Flyway inside `cashu-vault-jpa`, so the service is
  removed. An existing dev database needs
  `DROP INDEX IF EXISTS idx_keyset_unit_mint_unq;` once.

- A mint nobody has provisioned yet reports as empty rather than as the
  Operator's own typo. The vault client signals "this mint holds no keysets" two
  different ways — an `IllegalArgumentException` for an empty body on a `200`, and
  a `404` from the running vault — and the service caught the first alongside a
  malformed mint id, answering `400 invalid_mint_id`. Both signals now translate
  to an empty list in the adapter, so the page's not-provisioned state is
  reachable against a real vault.

- Every Operator role can read the audit trail. The dashboard's recent-activity
  panel is built from it, so `USER_ADMIN` and `OPS_ADMIN` — holding
  `dashboard:read` without `audit:read` — landed on a first screen whose main
  panel reported forbidden, and the audit page refused them outright. The trail is
  read-only and is how an Operator checks what was done to the deployment they are
  on call for.

- The admin gates the mint and operations pages on the permission rather than the
  role. The Super Administrator holds every permission and none of those pages'
  roles, so the account that exists to recover a deployment saw a dashboard and
  nothing else. A page can no longer be granted by the API and refused by the UI.

- Signing out wipes the enrolled browser key. It cleared the session but left the
  encrypted key in `localStorage`, so the login page still saw a stored key and
  offered only a passphrase box — the next person could not sign in with their own
  key at all. An idle lock still keeps the key on purpose, since the same Operator
  is coming back; signing out is how someone hands the browser over.

- The admin Docker image builds. The Dockerfile named a module path and a jar name
  that do not exist, and `.dockerignore`'s `**/out` swallowed the hexagonal
  `port/out` and `adapter/out` packages — 50 source files — out of every build
  context.

### Documentation

- **Audited `cashu-mint`, `cashu-lib` and `cashu-wallet` against the NUT
  specifications at pinned commit `49a909c`, and recorded the result in
  `docs/explanations/nut-compliance-audit.md`.** Fourteen divergences, tracked as
  21 issues across the three repositories. The interoperability-breaking ones are
  the secret encoding fed into `hash_to_curve`, the non-spec error wire format,
  and NUT-11 `SIG_ALL`, which verifies each input and output separately instead of
  signing one aggregated message. Fees turn out not to be wired up at any layer:
  `input_fee_ppk` is never set, is absent from `/v1/keysets`, is priced off the
  wrong keyset, is ignored by the wallet, and the swap path enforces two
  contradictory balance equations that only agree when the fee is zero. Keyset ID
  v2 and NUT-20 are carded rather than scheduled.

- **Audited every document against the code and fixed or deleted what no longer
  described it.** The admin API reference listed 13 endpoints that do not exist
  (all of `/admin/alerts/*`, `/admin/configuration/*`, `/admin/health/*`) and
  omitted two that do; it is now generated against the controllers and states the
  real roles, permissions and pagination fields. `admin-rest-api.md` is deleted as
  a strict duplicate. The E2E how-to prescribed a command that cannot work: the
  suite starts its own Testcontainers stack and needs `-Pe2e-tests -am`. Docs
  still described `vault-db-seed`, the SQL seeding path, and gateway classes and
  artifacts under their pre-rename names.

- **`audits/` removed, and the local-only `project/` and `specs/` directories
  deleted from the working tree.** Both were already gitignored. The operator
  procedure that lived in `specs/004/quickstart.md` — salt generation, backfill,
  retention purge, forensic lookup, salt rotation — is absorbed into
  `docs/runbooks/voucher-data-minimisation.md`, which was previously a pointer to
  it. Ten Javadoc citations of `specs/` paths now state their rule inline, and two
  runtime error messages point at the runbook instead of a file that no longer
  exists.

- The admin user guide drops the sections documenting removed features (alerts,
  health monitoring, configuration governance) and the invocations of a CLI that
  was deleted, losing a third of its length. `admin-triage.md` is re-verified:
  rotation and RBAC now actuate, retirement stops signing, and mint-side
  suspension exists but nothing in the admin writes it.

### Changed

- **The dev and E2E stacks run a vault-backed mint, so the admin and the mint are
  one system.** The mint served keysets from `preload-test-data.json` because
  `PreloadMintLoadService` is `@Primary` and on by default, and neither compose
  file disabled it. A mint the admin provisioned, and every rotation of its
  keyset, therefore landed in the vault and was invisible at `/v1/keysets`. Both
  stacks now set `MINT_PRELOAD_ENABLED=false` and point the mint at HashiCorp.
  `KeyRotationE2EIT` is no longer `@Disabled`: it asserts a rotation against the
  mint rather than against the admin's own echo.

- Seeding the dev keyset is separate from serving it. `VaultPreloadSeeder` seeds
  the vault at startup from the same JSON, so disabling preload no longer takes
  the bootstrap data with it. The `vault-db-seed` compose service is removed: it
  loaded SQL inserting `t_key.private_key`, a column dropped when key material
  moved to HashiCorp, so every run failed and seeded nothing. Key material cannot
  be seeded over SQL, because only the backend-aware vault knows where the secret
  goes.

- The operational controls listing reports `controlType` and `outcome`. Without
  them an operator could see that a rotation completed but not which keyset
  replaced which. The admin UI showed a permanently blank "Reason" column bound
  to a field the API never sent; it now shows the type and the outcome.

### Fixed

- **Provisioning no longer gives a unit a second active keyset.** A mint whose
  keyset was established by something other than the create-mint saga would get a
  second one, leaving two keysets claiming to sign for the unit; the next
  rotation then archived both and could not say which it replaced. An existing
  active keyset now stands.

- Key rotation no longer fails on every attempt. The vault permits a mint one
  *active* keyset per unit, so provisioning the replacement before archiving the
  keyset it replaces was rejected outright and every rotation ended
  `KEY_ROTATION_FAILED`. A rotation now archives its predecessor first and
  reinstates it if the replacement cannot be provisioned, so a failure part-way
  never leaves the mint unable to sign. A mint holding no keysets is treated as
  nothing to supersede rather than as a vault failure.

- The dev stack no longer re-creates the constraint that makes rotation
  impossible. A `vault-db-init` service applied `V1__init_schema.sql` with `psql`
  on every `up`, restoring the `UNIQUE (unit, mint_id)` index that migration `V5`
  drops precisely so a rotated-away keyset can coexist with its replacement. The
  vault schema is owned by Flyway inside `cashu-vault-jpa`, so the service is
  removed. An existing dev database needs
  `DROP INDEX IF EXISTS idx_keyset_unit_mint_unq;` once.

## [0.31.0] - 2026-08-28

### Changed

- **BREAKING** The admin API is reachable only with a NAP session. The shared
  admin token, the path-to-role RBAC filter, the bootstrap identity that held
  every role and the bespoke `/admin/auth/me` endpoint are deleted; NAP's
  `/api/v1/auth/session` replaces the last of them. Every admin controller now
  names the permission it requires, so an Operator without it is refused with a
  `forbidden` error body rather than a bare status. Per-operator credentials and
  their reset workflow are gone with the token: a migration drops
  `credential_hash`, `reset_count` and `reset_requested_at`, deletes rows with no
  public key, and makes `pubkey` required. The Super Administrator is configuration,
  not data: the role cannot be written through the admin API and a stored profile
  carrying it is ignored, so an Operator who may edit roles can no longer grant
  themselves the role that outranks them. Both admin API test suites sign in
  through a real handshake behind one helper, so nothing authenticates in tests
  that could not authenticate in production. Nothing is migrated and no fallback
  is kept — no deployment exists to migrate. (#373)

- Admin audit entries name the Operator who acted, taken from the authenticated
  session rather than from a request field. A caller could previously attribute
  their own action to anyone. (#370)

### Added

- Operators can authenticate to the admin API with their Nostr key (NAP), alongside
  the existing token. A completed NIP-98 handshake yields a session whose role and
  permissions come from one `AclResolver`: the configured Super Administrator npub
  (read from the environment as bech32, compared as hex, never looked up) first,
  otherwise the Operator profile. An npub with no profile, or a suspended one, is
  refused — authenticating grants no default role. Startup fails when the
  super-admin npub is missing or does not decode. Role and permission names are
  declared in `mint-admin-core`, which has no NAP dependency; NAP's own `AclStore`
  is unused, since it cannot list, update or delete and so cannot express Operator
  management. NAP's migrations run unmodified in a `nap` schema with their own
  history table; the admin's moved to `db/migration-admin` so the recursive scan
  cannot pick them up. Operator profiles gained a nullable `pubkey` column.
  Sessions are 15 minutes idle / 12 hours absolute with refresh tokens off, on an
  `HttpOnly`, `SameSite=Lax`, production-`Secure` `cashu_admin_session` cookie.
  On by default (`NAP_ENABLED`), and since #373 the only way in. (#372)

- The Super Administrator can provision, re-role, suspend and reinstate Operators
  through `/admin/users`. Suspension rather than deletion: an Operator named in the
  Audit Trail has to stay resolvable, so the row survives with `active = false` and
  the resolver refuses them on their next request. SUPER_ADMIN cannot be assigned
  through the API and the configured Super Administrator cannot be modified through
  it, so the account that recovers the deployment cannot be edited out of existence
  by someone who may edit roles. Every change writes an `operator_access_audit` row
  in the same transaction as the change itself. (#374)
- Operators sign in to the admin UI with a NIP-07 browser extension, or with a key
  encrypted in the browser under a passphrase for machines with no extension. The
  in-browser key is held only for the session; a reload asks for the passphrase
  again rather than keeping a decrypted key at rest. (#375, #376)
- An Operator management page: the listing names each Operator by npub, shows the
  configured Super Administrator marked as configuration-anchored rather than
  editable, and offers suspend / reinstate with a reason. The page is gated on the
  `users:manage` permission rather than a role, so the Super Administrator — who
  holds every permission and no page's role — reaches the pages they exist for. (#378)
- ADR 0008 records why authorisation is resolved by the admin rather than delegated
  to NAP's `AclStore`, and a how-to covers configuring the super-admin npub, the two
  startup failures, and enrolling the first Administrator. (#379)

### Fixed

- Dropped a readiness alert that could never fire: it tested an expression no
  exporter in the stack publishes, so it read as coverage while watching nothing.

- The mint could not boot with `cashu.mint.jpa.enabled=true`. `byte-buddy` was
  pinned to `test` scope in the parent `dependencyManagement`, which overrides
  the compile-scope transitive dependency Hibernate declares for its
  `BytecodeProviderImpl`, so the jar never reached the runtime image. The mint
  started, applied all 18 Flyway migrations, and only then failed building the
  `EntityManagerFactory` with `NoClassDefFoundError:
  net/bytebuddy/description/type/TypeDefinition`. Every unit-test boot and every
  dev profile ran without JPA, so nothing exercised the path. `byte-buddy-agent`
  stays test-scoped; only Mockito uses that one.

- Grafana dashboards no longer read empty. Three independent faults each broke
  the pipeline on their own, and all three were invisible because the failure
  mode of every one of them is a blank panel rather than an error:
  - Task metrics were tagged `task` while every dashboard, alert rule and the
    metrics reference grouped by `task_name`. The series existed, so nothing
    errored; `sum by (task_name)` just silently collapsed every task into one
    unlabelled line.
  - Request, task and lock timers published no histogram buckets, so Micrometer
    exported Prometheus *summaries*. Summaries carry count, sum and max but no
    `_bucket` series, so every `histogram_quantile` panel and every latency SLO
    alert resolved to no data.
  - Prometheus scraped a hardcoded `cashu-mint-rest-dev:9000`, a hostname that
    resolves only on the dev stack. Anywhere else no target matched, which makes
    `up{job="cashu-mint"}` *absent* rather than `0`, so even `CashuMintDown`
    stayed quiet.
- The two voucher dashboard panels that query Prometheus had a datasource with
  no `uid`, so they resolved against whichever datasource Grafana defaulted to.

### Added

- `RuntimeClasspathTest` asserts that `byte-buddy` and `hibernate-core` are on
  the *runtime* classpath, so the JPA boot path cannot lose its bytecode
  provider again. It reads the classpath recorded by `maven-dependency-plugin`
  rather than calling `Class.forName`, because a test-scoped dependency is
  present on the test classpath and an in-JVM check passes while the image is
  broken. Verified in both directions: red with the scope restored, green with it
  removed.
- `ScrapeContractTest` guards the three runtime-composed metric families
  (`cashu_mint_requests_*`, `cashu_mint_task_*`, `cashu_mint_lock_*`) that
  `MetricCatalogueContractTest` exempts by design. It drives the real
  instrumentation into a real `PrometheusMeterRegistry` and asserts against the
  actual scrape text that every timer family exports `_bucket` series and that
  every label a dashboard or alert groups or filters by exists on the emitted
  series. The exempted families were the only ones nothing checked, and all
  three had drifted.
- `Cashu Mint Integrity` dashboard, covering the 15 of 17 declared recorder
  metrics that no dashboard charted — including every money-at-risk invariant
  (`cashu_mint_melt_payment_sent_burn_failed`,
  `cashu_mint_melt_stuck_payment_unknown`, `cashu_mint_voucher_orphan_issuance`)
  that a `critical` alert fires on. Those alerts previously paged with no
  dashboard to land on. It also charts
  `cashu_mint_invariant_poll_failures_total` beside those gauges, since a stale
  poll leaves them reading a falsely reassuring zero.
- Prometheus scrape targets now come from file service discovery
  (`prometheus/targets/*.yml`, overridable with
  `CASHU_PROMETHEUS_TARGETS_DIR`), so one config works across dev, staging and
  production.

### Changed

- Dashboard titles say which question each one answers, and the mint dashboards
  are prefixed so they group together in Grafana's list: `Cashu Mint — Service
  Health`, `— Task & Endpoint Breakdown`, `— SLO & Error Budget`, `— Lock
  Contention & Threads`, `— Money at Risk`, and `Vouchers — Outstanding
  Liability` / `— Token Reconciliation` / `— IOU Exposure`. UIDs are unchanged:
  they are the stable link target for bookmarks and alert `runbook_url`
  annotations, so renaming one breaks every existing link silently.

### Removed

- `Cashu Mint Business` dashboard, superseded by `Cashu Mint Integrity`. Its two
  panels charted a cumulative counter as a bare total and its rate; both are
  retained on the new dashboard, broken down by funding source.

## [0.30.0] - 2026-08-19

### Removed

- **BREAKING** `mint-admin-cli` module, and the `/admin/health`, `/admin/alerts`
  and `/admin/configuration` endpoints. None of them worked: health had no
  producer and read `UNKNOWN` forever, alerts held only hand-typed rows with no
  detection or delivery, configuration was stored and never applied, and the CLI
  presented stub data as live in connected mode. Monitoring is
  `cashu-mint-observability`'s job. The `ALERTS_ADMIN` role is gone.
  `ConfigurationSet` and `NotificationPolicy` are kept — they are `MintAggregate`
  state, not feature state. (#364)

### Changed

- **BREAKING** Admin authorisation no longer reads roles from the `X-Admin-Roles`
  request header. Operators authenticate with their own credential and their
  roles are resolved from the operator store. Credentials are random and stored
  only as SHA-256 hashes; every previously issued reset token stops working.
  `/admin/audit/**` now requires a role. (#362)
- **BREAKING** `MintProtocolService` gains `requireActiveKeySet`. The mint refuses
  to sign new outputs against an archived keyset, returning `keyset_inactive`.
  Redemption of proofs an archived keyset already signed is unaffected. (#361)

### Added

- Key rotation actually rotates: `ROTATE_KEYS` drives an outbox saga that
  generates a replacement keyset in the shared vault and archives the one it
  supersedes, idempotent on the operational control id, with compensation if the
  archive step fails. Outcomes are recorded in `operational_controls.outcome`
  with both keyset ids. (#363)

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [0.29.0] - 2026-08-18

Replaces the mint's dead instrumentation with a typed recorder seam, adds
DB-derived gauges for the three money-losing invariants, and makes both
directions of the metric catalogue checkable by CI (#337).

### Removed

- **BREAKING** `MintIntegrityContext.meterRegistry()`. Deleted, not deprecated:
  a deprecated accessor preserves the exact hole this work exists to close —
  the next spec adds counter number seventeen inline and the catalogue drifts
  again. Domain code reaches metrics only through the typed recorder ports in
  `xyz.tcheeric.cashu.mint.proto.metrics`. The registry no longer threads
  through `NUT04 → MintTokensTask → MintTask`, so the public constructors of
  both tasks lost their trailing `MeterRegistry` parameter, and the null checks
  that guarded every former call site are gone (#343).

### Changed

- **BREAKING** Metric renames. Dashboards and alert rules querying the old
  names return no data:
  - `cashu_mint_voucher_funding_required_total`,
    `cashu_mint_voucher_face_value_not_backed_total` and
    `cashu_mint_voucher_iou_denied_total` collapse into
    `cashu_mint_voucher_rejected_total{reason=...}`, with the label domain
    bounded by an enum. They were always one metric with a reason dimension;
    splitting them meant a new reason cost a new metric, panel and alert rule
    (#341).
  - `cashu_mint_quote_expired_total` → `cashu_mint_issuance_quote_expired_total`
  - `cashu_mint_amount_mismatch_total` → `cashu_mint_issuance_amount_mismatch_total`
  - `cashu_mint_quote_cross_check_failures_total` → `cashu_mint_issuance_cross_check_failure_total`
  - `cashu_mint_idempotent_replay_total` → `cashu_mint_issuance_idempotent_replay_total`
- **BREAKING** The `path="mint"` label is gone from the issuance counters. With
  the area in the metric name it was a second encoding of the same fact (#342).
- **BREAKING** `cashu_mint_voucher_rate_limit_breach_total` no longer carries a
  `principal` label. Today's single ADMIN service account keeps it bounded, but
  under per-merchant authentication it becomes both a cardinality problem and a
  data-minimisation one: spec 004 kept merchant identity out of the read path
  with column-level GRANTs, and a Prometheus label routes it straight back out
  into a store with no retention purge. The principal stays in the logs (#341).
- The metrics reference is now generated from the recorder declarations rather
  than written by hand. It had documented roughly fifty metrics of which most
  were never emitted, because it was a third artefact with nothing tying it to
  the code (#348).

### Added

- Typed metric recorder ports for the voucher, mint/issuance, webhook and
  invariant areas, with Micrometer implementations in `cashu-mint-observability`
  (#341, #342, #343). Every meter is registered eagerly, so no family first
  materialises on failure — a family that only appears when something breaks
  reads as "no data", which is indistinguishable from a broken exporter.
- `InvariantGaugePoller` in `cashu-mint-jpa`: exports the three money-losing
  invariants as gauges re-derived from the database every 60 seconds, running
  the operator queries already documented on the repositories (ADR 0002).
  A counter incremented on a state transition can neither express "stuck for an
  hour" nor survive a restart with its standing count intact — exactly the
  wrong failure mode for conditions that by design never resolve themselves
  (#344, #345).
  - `cashu_mint_melt_stuck_payment_unknown` — sagas parked in `PAYMENT_UNKNOWN`
    past `cashu.mint.melt.payment-unknown-ttl`.
  - `cashu_mint_melt_payment_sent_burn_failed` — payment settled while the
    proofs stayed spendable.
  - `cashu_mint_voucher_orphan_issuance` — issued voucher value with no funding
    row.
  - `cashu_mint_invariant_poll_failures_total` — the gauges fail open, so a
    failing poll has to be alertable in its own right or a stale zero silently
    disarms the pages.
- Alert rules for all three, each carrying the SQL behind its gauge in a
  `query` annotation so the first diagnostic step ships with the page. Their
  shapes differ deliberately so an on-call reader can tell a stuck saga from
  one merely in flight: burn-failure pages on the first occurrence, Orphan
  Issuance sustains five minutes, Stuck Payment applies its TTL in SQL.
- `MetricCatalogueContractTest` — fails the build when a declared metric has no
  production call site, or when a name in dashboard PromQL or an alert rule
  resolves to no declaration. Nothing connected a declared metric to a consumer
  in either direction before; both failure modes had happened and both survived
  CI for a long time (#347).
- `MeterRegistryContainmentArchTest` — fails the build if a protocol class
  depends on `io.micrometer` again (#343).
- New configuration: `cashu.mint.invariant.poll-interval` (default `PT60S`).

### Fixed

- Both melt invariant gauges excluded operator-acknowledged sagas only after
  review. `MeltSagaAdminController#markResolved` deliberately appends a
  transition without overwriting `current_state` (FR-007 / FR-011), and nothing
  else moves a saga out of `PAYMENT_UNKNOWN` or `PAYMENT_SENT_BURN_FAILED` — so
  as first written, either alert would have paged forever with no in-product
  way to clear it.
- The Stuck Payment clock now runs from the last transition *into*
  `PAYMENT_UNKNOWN` rather than from `created_at`, so an old saga that has only
  just turned ambiguous is not reported as stuck for hours.
- `CashuMintErrorBudgetBurning`'s description contained `{{ 30 / 10 }}`, which
  is not valid Go template syntax, so the one alert whose job is explaining how
  fast the budget burns would have rendered without its explanation. Found by
  the promtool rule tests added in the deployment repository — nothing had ever
  evaluated these rules.
- The integration-test Postgres container had lost `fsync=off`: Testcontainers
  sets it in the constructor and `withCommand` replaces rather than appends.

## [0.28.0] - 2026-08-18

### Changed

- **BREAKING for anything probing or scraping actuator on port 7777.** Actuator moved to
  its own management port, default `9000` (`CASHU_MINT_MANAGEMENT_PORT`). Nothing under
  `/actuator/**` answers on the public API port any more, so `/actuator/prometheus` — which
  exposes issuance rates, outstanding liability and melt-saga failure counts — is no longer
  readable by anyone who can reach the mint. External stacks consuming
  `docker.398ja.xyz/cashu-mint-rest` with a health check or load-balancer probe on
  `7777/actuator/health` must retarget to the management port, or probe `/v1/info` on 7777.
  `management.server.address` defaults to `0.0.0.0`: isolation comes from not publishing the
  port, which also keeps Kubernetes `httpGet` probes (issued against the pod IP, never
  loopback) working. `ManagementPortGuard` fails startup if the management and application
  ports are ever set equal. Issue #346.

### Added

- `ManagementPortGuard` — fails startup when `management.server.port` equals
  `server.port`, is unset, or is `-1` (which disables the management server and folds
  actuator back onto the public port). The port separation is the security property this
  release exists for, and it was previously undoable by a single environment variable with
  no warning at boot.
- `ActuatorManagementPortIT` — asserts `/actuator/prometheus` responds on the management
  port and 404s on the application port, alongside health/readiness still reachable for
  container health checks. It drives `CASHU_MINT_MANAGEMENT_PORT` rather than
  `management.server.port`, so it exercises the shipped wiring: setting the Spring property
  directly would pass against a build with no management port configured at all.

## [0.27.0] - 2026-08-18

### Removed

- **Four metric classes that registered ~45 meters and were never called once.**
  `MintMetrics`, `QuoteMetrics`, `VoucherMetrics` and `GatewayMetrics` were wired
  as beans in `ObservabilityAutoConfiguration`, but every reference to them
  outside `cashu-mint-observability` was a doc or their own unit tests — no
  production call site incremented a single counter. The dashboards and alert
  rules charted that dead set, so those alerts could never fire, while the
  metrics the mint does emit appeared on none of them. Deleted the classes,
  their unit tests and their bean definitions. `TaskMetrics` and `LockMetrics`
  stay: they are the two families reached through the `TaskMetricsAdapter` /
  `LockMetricsAdapter` recorder ports in `cashu-mint-protocol`, and the only two
  that were alive.
- Configuration properties that only fed the deleted beans:
  `cashu.observability.metrics.track-keysets`,
  `cashu.observability.metrics.include-unit-tag`,
  `cashu.observability.metrics.application`,
  `cashu.observability.metrics.environment`,
  `cashu.observability.vouchers.enabled` and
  `cashu.observability.gateway.enabled`. The `MetricsProperties`,
  `VouchersProperties` and `GatewayProperties` nested classes are gone with
  them. Setting any of these keys was already a silent no-op.
- The **Cost Analysis** Grafana dashboard, whose every panel queried a
  `GatewayMetrics` or `MintMetrics` series.
- Dead panels from the **Overview**, **Operations** and **Business** dashboards.
  The **SLO**, **Virtual Threads** and three voucher dashboards are untouched.
- Alert rules reading deleted series: `CashuMintHighLiability`,
  `CashuMintDoubleSpendAttempts`, `CashuMintQuoteBacklog`,
  `CashuMintProofIssuanceSpike` and `CashuMintVoucherRejectionRate`, plus
  `CashuMintGatewayUnhealthy` and `CashuMintVaultUnhealthy` — the latter two
  watched `cashu_mint_gateway_health` (from the deleted class) and
  `cashu_mint_vault_health` (never emitted by anything at all). The
  corresponding Alertmanager inhibit rules went with them.
- The spec-004 `cashu_mint_voucher_compat` Prometheus recording-rule group. It
  aliased the deleted plural `cashu_mint_vouchers_*` names and was already
  marked for removal after one release cycle. Nothing in this repo consumed the
  aliases; external consumers of `cashu_mint_vouchers_issued_total` lose that
  series with no deprecation window.

### Fixed

- **The JPA context could not boot at all when `cashu.mint.jpa.enabled=true`.**
  `V20260601_007` (spec 035, shipped in 0.26.0) added `original_token_amount` to
  `voucher_quote` but not to its Envers shadow `voucher_quote_aud`, while
  `VoucherQuoteEntity` is `@Audited` at class level. Hibernate's schema
  validator expects the column on both, so `mintEntityManagerFactory` failed to
  build and took the whole application context down — every integration test
  that boots the stack errored on `Unable to start embedded Tomcat`. Added
  `V20260601_008` to bring the shadow table in line. Audited by the same check
  across all seven `@Audited` entities; this was the only drift (the `version`
  columns are `@Version`, which Envers excludes by design).

### Changed

- Updated `cashu-voucher` to 0.10.0 (from 0.6.1).
- `cashu-mint-rest` no longer attaches its ~110 MB `-exec` fat jar as a Maven
  artifact (`<attach>false</attach>` on the Spring Boot `repackage` goal). The
  jar is still built into `target/` — the module's Dockerfile and
  `scripts/heap-exhaustion-test.sh` both read it from there — but it is no
  longer installed or deployed, where it exceeded the repository upload limit
  and failed `mvn deploy` with HTTP 413. The thin `cashu-mint-rest` jar is
  still published for use as a dependency.
- Integration tests raise `cashu.mint.issuance.rate-limit.per-minute-burst`
  in the shared `test` profile. Spring's `ApplicationContext` cache is shared
  across IT classes in a JVM fork, so `IssuanceRateLimitFilter`'s token bucket
  is shared too and later classes hit `POST /v1/mint` against an already-drained
  bucket. The filter stays enabled so it is still exercised.
- `cashu-mint-observability/docs/metrics-reference.md`, `docs/reference/configuration.md`,
  `docs/reference/module-layers.md`, `docs/how-to/enable-observability.md` and the
  Observability section of `CLAUDE.md` now describe only metrics the mint emits.
  Added the previously undocumented `cashu_mint_lock_*` family. Noted explicitly
  that the gateway and vault health indicators surface on `/actuator/health`
  only and are not exported as Prometheus series, so neither is alertable
  without an external probe.

Every `cashu_mint_*` name still referenced by dashboard JSON or an alert rule
now resolves to something the mint actually emits.

## [0.26.0] - 2026-08-18

### Fixed

- **P2PK spends were rejected for every spec-conformant public key.** `P2PKSpendingCondition`
  passed the 33-byte compressed key straight to `Schnorr.verify`, which requires the 32-byte
  BIP-340 x-only form and throws on anything else. That throw landed in a `catch (Exception)` that
  logged and continued, so the key silently counted as *no valid signature* and the spend failed as
  under-signed. The mint therefore only worked against 32-byte x-only keys — the form NUT-11
  forbids, and the form its own test fixtures built. The parity prefix is now stripped via the
  `xCoordinate` helper that already sat one method away (it was used for dedup counting but not for
  the verify call). Wallets sending compressed keys — cashu-ts, CDK, imani-wallet-lib — were
  affected.

### Added

- **A malformed P2PK lock returns an unspendable-proof error instead of a 500.** NUT-11 frames a
  malformed P2PK secret as a Proof that MUST be rejected as unspendable — a client error, not a
  server fault. Two handlers on `CashuController`: one for `MalformedP2PKSecretException` itself
  (service-layer parsing), and one unwrapping it from `HttpMessageNotReadableException`, since
  Jackson wraps deserializer exceptions during `@RequestBody` binding and it never arrives as
  itself from a request body. Both return `verify_proof_failed_error` with HTTP 400; an unrelated
  unreadable body stays an ordinary 400.

### Changed

- Updated cashu-lib to 0.21.0 (NUT-11 P2PK secret validation). Validation is fail-closed: a
  malformed lock is now rejected at parse time rather than accepted and misbehaving later. Test
  fixtures that built locks from 32-byte x-only keys were corrected to compressed keys.

## [0.25.0] - 2026-07-13

### Added

- **NUT-11 refund path now honors `n_sigs_refund`** (multi-sig refund threshold),
  consuming cashu-lib 0.19.0 — an escrow with `n_sigs_refund > 1` now requires that
  many valid refund signatures to reclaim, instead of any single one. Enforced only
  after `locktime` and counted over **distinct** refund pubkeys, on both the input
  refund signatures and `SIG_ALL` output witnesses. Backward compatible: escrows
  without `n_sigs_refund` set default to a threshold of 1, identical to the prior
  behavior. **`n_sigs_refund` is a Dalia extension to NUT-11** (not in the published
  spec): it is a structurally valid NUT-10/11 tag and backward compatible, but the
  multi-sig refund guarantee is enforced only by mints that implement this tag — a
  standard mint would ignore it and permit a single-signature (1-of-N) refund.

## [0.24.0] - 2026-07-13

### Added

- **Dalia zero-value IOU keyset (Phase 9)** — a dedicated keyset (`unit="iou"`)
  whose issuance is blind, payment-exempt, and allows `amount==0` markers, while
  all melt/swap of IOU proofs is refused (issuance + checkstate only; IOU tokens
  are non-transferable). The mint never inspects the IOU secret. Marked by
  convention via `IouKeysets`.
- **Per-identity mint issuance rate limit (Phase 9)** — a `/v1/mint/**` filter with
  a per-minute burst (default 10) + per-day quota (default 60) per identity, keyed
  by the engine-supplied `X-Dalia-Identity` header else the remote address. 429 +
  `Retry-After` + a Micrometer breach counter. Configurable under
  `cashu.mint.issuance.rate-limit.*`.

### Fixed

- **NUT-11 locktime semantics** — the primary n-of-m multisig is now spendable at
  any time (before/after locktime); the refund keys reclaim only after the
  locktime (or, absent refund keys, the proof unlocks). Previously a valid
  multisig spend was rejected during the lock window and the refund path never
  worked. **NUT-11 P2PK is now enforced at melt (redemption)**, not only at swap —
  previously melt performed a BDHKE check only, so a P2PK-locked proof could be
  cashed out without a valid witness.
- **NUT-11 P2PK determinism + error handling** (review hardening) — the secret is
  now hashed as **UTF-8** (was the platform default charset) on both the primary
  and refund paths, so witness verification is portable across environments. A
  malformed `sigFlag` and missing/unsigned outputs under `SIG_ALL` are now rejected
  as **protocol errors** (`CashuErrorException`) instead of surfacing as HTTP 500s.

### Security

- **Issuance rate-limit identity hardening** (review) — the client-supplied identity
  header is stripped of control characters (CR/LF log-injection defense) and capped
  in length before use as a cache key + log field, bounding per-identity memory.

---

## [0.23.0] - 2026-06-21

### Added

- **Traceability producer (spec 036)** — the mint can emit signed `kind-9079`
  trace events to the `cashu-ledger` forensic ledger: `MINT_QUOTE_REQUESTED` /
  `MELT_QUOTE_REQUESTED` on quote creation, and `MINT_FAILED` (no proofs) /
  `MELT_FAILED` (released inputs by public `Y` only) on the two failures the
  mint owns. Disabled by default (`cashu.trace.publisher.enabled=false`); when
  enabled, boot fails closed unless the signing key, relays, and `cashu.mint.url`
  are set. Fire-and-forget — a tracing fault never fails or blocks a mint
  operation. New code is confined to `cashu-mint-rest`; see
  `docs/how-to/enable-trace-producer.md`. Consumes the
  `cashu-ledger-trace-publisher` starter.

### Changed

- Bumped `nostr-java` `1.3.0` → `2.0.7` (the trace publisher signs over
  `nostr-java-core` 2.x; 2.x consolidated the module set into
  `core`/`event`/`client`/`identity`).
- `AsyncConfig` now enables `@Async` unconditionally (only the virtual-thread
  executor stays conditional), so disabling virtual threads no longer turns
  `@Async` methods into synchronous calls on the request thread.

---

## [0.22.0] - 2026-06-06

### Fixed

- **NUT-04 v1 mint-quote responses** — `MintQuoteTask`,
  `MintQuoteStatusTask`, and `VoucherMintQuoteTask` now emit `amount`,
  `unit`, and `state` on the mint-quote response. Modern wallets
  (cashu-ts `>= 4.x`) normalize every response and threw
  `AmountError: Unsupported amount input type` on the legacy v0 shape,
  blocking all client-side minting (imani spec 041). `state` is derived
  from the durable `mint_quote.lifecycle_state` (with a payment-flag
  fallback when no quote repository is wired); the deprecated `paid`
  boolean is still emitted for v0 consumers. Relative `expiry` is passed
  through unchanged — the client normalizes relative-vs-absolute itself.

### Changed

- Bumped `cashu-lib` `0.17.0` → `0.18.0` for the NUT-04 v1
  `PostMintQuoteResponse` fields (`amount`/`unit`/`state`).

## [0.21.0] - 2026-06-05

### Added

- **Spec 041 Phase 1 — bare `GET /v1/keys` NUT-01 listing.** Aggregates per-keyset
  NUT-02 lookups so cashu-ts 4.x's `Wallet.loadMint()` bootstrap call resolves
  cleanly. Prior versions only exposed the NUT-02 two-step pattern
  (`/v1/keysets` + `/v1/keys/{id}`), which broke any client using cashu-ts as the
  high-level wallet surface. See `imani-apps/packages/client-mint/CASHU_TS_API.md`
  §5 Finding 1 for the integration trace.
- **Spec 041 Phase 0 REQ-MINT-3 — strict NUT-04 quote expiry.** `MintTask` now
  computes `createdAt + getPaymentExpiry()` via the new `Gateway.getCreatedAt`
  port (payment-adapter 0.13.0) and throws a deterministic `quote_expired` error
  when the call lands past that instant. Falls through permissively when the
  gateway returns null `createdAt` (backward compatibility for rows persisted
  before the column was added). Phase 0 sign-off issued 2026-06-05 against
  staging running this code — see
  `imani-apps/specs/041-client-side-voucher-minting/contracts/abandoned-mint-recovery.contract.md`.

### Changed

- Updated `payment-adapter` to `0.13.0` (picks up the new `Gateway.getCreatedAt`
  port and the `GatewayQuote.createdAt` JPA column required by the expiry
  enforcement above).
- **Spec 041 T001 — CORS configuration for the public NUT surface.** Added a
  `CorsConfigurationSource` bean to `SecurityConfig` that allows GET/POST/OPTIONS
  on `/v1/**` and `/webhook/**` from origins configured via
  `cashu.mint.cors.allowed-origins` (env var
  `CASHU_MINT_CORS_ALLOWED_ORIGINS`, comma-separated). Falls back to wildcard
  when unset (safe for the unauthenticated public NUT surface; operators MUST
  set the env var to their wallet origin(s) on production). Required for
  browser-side cashu-ts to reach the mint at all.

---

## [0.20.0] - 2026-05-29

### Added

- **Spec 035 — voucher provenance lookup for the wallet's partial-spend
  display correction.** A cashu V4 voucher token's embedded
  `SignedVoucher.face_value` is frozen at original issuance and does not
  reflect partial spends. To let a receiving wallet correct the display
  for a partial-spend portion, the mint now exposes the original
  sat-denominated proof sum so the wallet can compute
  `derived = round(current_token_amount × face_value / original_token_amount)`.
  - **New REST endpoint** `GET /v1/vouchers/{voucherId}/provenance`
    returning `{ voucherId, faceValue, unit, originalTokenAmount,
    issuanceRatio, lifecycleState }`. `issuanceRatio` is computed
    strictly as `face_value / original_token_amount` (never from a
    current proof sum, which would reconstruct the frozen original).
    Returns `null` for both `originalTokenAmount` and `issuanceRatio`
    on legacy rows issued before this release; the wallet's source-chain
    resolution already accepts null and falls back to the embedded
    face_value path.
  - **New `voucher_quote.original_token_amount BIGINT NULL` column**
    (Flyway `V20260601_007`). Captured at voucher issuance time as
    `sum(blindedMessages)` in `MintTask`, written atomically with the
    `ISSUING → ISSUED` CAS via the new
    `VoucherQuoteRepository.recordIssuance(quoteId, originalTokenAmount)`
    port method. No backfill from `voucher_issuance.outputs_hash` —
    null cleanly means "legacy / unavailable" and is the documented
    fallback.

### Changed

- **`VoucherQuoteRepository` port** gains
  `int recordIssuance(String quoteId, long originalTokenAmount)`. Adapters
  must implement; in-memory test fixtures already get a no-op via the
  default `originalTokenAmount()` method on the `VoucherQuote` interface.
- **`MintTask`** voucher branch now calls `recordIssuance` instead of a
  bare `casLifecycle(ISSUING → ISSUED)`, so the lifecycle close and the
  amount capture commit together in a single UPDATE (no half-state with
  ISSUED but NULL amount, which would silently mis-flag the row as
  pre-migration legacy).

### Documentation

- `docs/explanations/voucher-data-record.md` adds a row for
  `original_token_amount` to keep the disclosure-doc schema-contract
  test green.

---

## [0.19.5] - 2026-05-25

### Fixed

- **PR #331 review follow-up.** `PaymentWebhookController`:
  - A missing/empty request body now returns `400 "Missing notification
    payload"` (a client mistake) instead of `401` — the signature validator
    is no longer consulted for an absent body, restoring the documented
    `400 invalid input` vs `401 auth failure` distinction.
  - The deserialize-failure log no longer includes the Jackson exception
    message (which can embed untrusted payload snippets, e.g. a preimage);
    it logs only the exception type at WARN, with the full stack trace at
    DEBUG.

---

## [0.19.4] - 2026-05-25

### Fixed

- **Spec 008 — conform the mint to the payment-adapter→mint webhook
  contract.** Closes the High finding in the 2026-05-24 backend token
  integrity review: a real adapter webhook could not be processed even
  with a shared HMAC secret.
  - `PaymentNotification` now maps the adapter's snake_case wire names
    (`quote_id`, `payment_method`, `receipt_id`, `paid_at`) via
    `@JsonProperty` (+ `@JsonAlias` for camelCase back-compat), adds a
    `unit` field, and `@JsonIgnoreProperties(ignoreUnknown = true)`.
    Previously a real payload deserialized `quoteId`/`paymentMethod`/
    `paidAt` as null and was rejected with `400 "Missing quoteId"`.
  - `WebhookSignatureValidator` now HMACs the EXACT raw request body
    bytes instead of a re-serialised DTO (the camelCase re-serialisation
    never matched the adapter's snake_case payload).
    `PaymentWebhookController` reads `@RequestBody byte[]` and authenticates
    over those bytes before deserialising.
  - Adds a golden-payload compatibility test + a real-validator end-to-end
    controller test. No adapter change; no schema change.

---

## [0.19.3] - 2026-05-25

### Fixed

- **PR #330 review follow-up — deterministic output errors now return a
  clean 4xx (not 500), and a null output no longer NPEs.** Spec 007 made
  `validateDenominations` throw typed `ErrorResponse` JSON, but
  `CashuController#handleCashuError` only mapped an allowlist of codes to
  `400`, so the new codes (and the pre-existing voucher
  `mint_amount_mismatch`) still surfaced as `internal_error`/500.
  - Extended the controller's code → HTTP status mapping to include
    `invalid_output_amount`, `invalid_denominations`, `missing_keyset_id`,
    `mint_request_missing_outputs`, `mint_request_contains_null_output`,
    and `mint_amount_mismatch` → `400`.
  - Added an up-front null-output guard in `MintTask.doExecute` (a
    null-safe loop, before any amount-sum/hash stream) so a `null` output
    returns `mint_request_contains_null_output` (4xx) instead of NPE-ing
    into a 500 in the amount-sum stream.
  - Tightened the spec-007 ITs to assert `is4xxClientError()` and added a
    null-output regression case.

---

## [0.19.2] - 2026-05-24

### Fixed

- **Spec 007 — validate deterministic output shape before the issuance
  lifecycle CAS.** Closes the High finding in the 2026-05-24 backend token
  integrity review: `MintTask` advanced the quote into `ISSUING` before
  running deterministic output validation, so a malformed-but-amount-summing
  request (e.g. `[11, -1]` for a 10-sat quote, or a non-canonical split)
  consumed a paid/funded quote into `ISSUING` and stranded it there.
  - Regular path: `validateDenominations` now runs immediately before the
    `PAID → ISSUING` CAS (gated on `lifecycleState == PAID`); for an
    already-advanced quote the CAS still yields `quote_already_issued` /
    `issuance_in_progress`. `quote_not_found` / `amount_mismatch` precedence
    is unchanged.
  - Voucher path: the `FUNDED → ISSUING` CAS is moved out of
    `resolveVoucherFunding` into `advanceVoucherToIssuing`, called only after
    the face-value output-sum check.
  - `validateDenominations` now throws typed `ErrorResponse` JSON, so a
    deterministic client error surfaces as a clean 4xx
    (`invalid_output_amount` / `invalid_denominations` / `missing_keyset_id`
    / `mint_request_missing_outputs` / `mint_request_contains_null_output`)
    instead of being mapped to `internal_error`/500.

---

## [0.19.1] - 2026-05-24

### Fixed

- **PR #329 review follow-up — preserve the FR-014 IOU-attempt alert on
  policy-denied paths.** `cashu_mint_voucher_iou_issued_total` is now
  incremented for **every** `MERCHANT_IOU` issuance attempt (before the
  policy gate), restoring the "regardless of policy" semantics existing
  dashboards/runbooks rely on. Under `DENY` the attempt still throws
  `iou_not_permitted` and additionally increments
  `cashu_mint_voucher_iou_denied_total`. Previously the issued counter
  fired only on the `ALLOW` path, blinding monitoring exactly in the
  denied scenario.

---

## [0.19.0] - 2026-05-24

### Security

- **Spec 006 — Enforce a reliable value-backing invariant for voucher issuance.**
  Closes the second Critical finding in the 2026-05-24 backend token
  integrity review: the mint signed full **face-value** Cashu for the
  `customer_paid` voucher variant while only the **fee** (`charged_amount`)
  was backed, and nothing verified the funding amount/unit. The
  customer-paid path was also operationally broken — a real voucher
  payment webhook was misclassified as `orphan`.
  - `MintTask` gains a fail-closed `enforceFaceValueBacking` gate that runs
    **before** the `FUNDED → ISSUING` CAS and before signing. The funding
    attached to the quote must cover the face value in the quote's unit:
    `CUSTOMER_PAYMENT` → `face_value_not_backed` (a fee payment can never
    back face value); `MERCHANT_DEBIT` → requires `amount >= face_value`
    and matching unit; `MERCHANT_IOU` → `iou_not_permitted` unless
    `cashu.mint.voucher.iou-policy == ALLOW`, then must also cover face
    value. A denied quote stays `FUNDED` and never signs.
  - `QuoteStatusUpdater` now falls back to `voucher_quote` on a `mint_quote`
    miss: a voucher payment with `amount == charged_amount` is classified
    `accepted` (was `orphan`) so the `CUSTOMER_PAYMENT` funding resolver can
    bind it; wrong amount → `amount_mismatch`.

### Added

- **FR-006 IOU policy enforcement (Medium finding).** `cashu.mint.voucher.iou-policy`
  was installed into `MintIntegrityContext` but never read; it is now
  enforced at issuance (`iou_not_permitted` when `DENY`).
- New typed errors `face_value_not_backed`, `iou_not_permitted`.
- Metrics `cashu_mint_voucher_face_value_not_backed_total`,
  `cashu_mint_voucher_iou_denied_total`.

---

## [0.18.1] - 2026-05-24

### Fixed

- **PR #328 review follow-ups (spec 005 fail-closed path).**
  - Refund-failure recovery (Codex P1): if `refundForSaga` throws while
    releasing a partial hold, the saga is now left in `PROOFS_HELD` (not
    forced to `FAILED`) so `MeltSagaReconciler.sweepStaleProofsHeld` can
    retry the refund — forcing `FAILED` would strand the proofs in
    `PENDING` with no automatic recovery.
  - Consistent terminal error (Copilot): every bind-failure path
    (normalize error, vault exception, partial bind) now throws the
    `proofs_not_bound` terminal error rather than leaking
    `melt_proof_pending_error` / the raw vault cause.
  - Renamed `buildNormalisedProofEntities` / `normalisedProofs` to the
    American spelling used elsewhere in the codebase.
  - Tests assert the `proofs_not_bound` code on the vault-exception paths
    (unit + IT) and add a refund-failure-leaves-PROOFS_HELD case.

### Changed

- Updated cashu-vault 0.9.0 → 0.9.1 (insert-or-claim hardening).

---

## [0.18.0] - 2026-05-24

### Security

- **Spec 005 — Enforce durable melt-saga proof holds before external payment.**
  Closes the highest-priority finding in the 2026-05-24 backend token
  integrity review: the melt saga recorded `PROOFS_HELD` but the durable
  hold was not actually enforced before `lightningPaymentPort.pay`, so a
  melt could pay externally against zero or partially-bound proof rows.
  - `MeltTask` now performs a single atomic insert-or-claim per melt via
    the new `ProofVaultService.insertOrClaimForSaga`, after Y-normalising
    every proof through `ProofEntity.fromProof` (the canonical identity
    already used on the burn/SPENT path). This eliminates both the
    insert-then-claim no-op and the raw-secret/Y duplicate-row class.
  - **Fail-closed:** if not every submitted proof is durably bound (or the
    vault call throws), the saga releases any partial hold via
    `refundForSaga`, transitions `PROOFS_HELD → FAILED`, caches a terminal
    `proofs_not_bound` error, increments
    `cashu_mint_melt_proofs_not_bound_total`, and throws **before**
    `lightningPaymentPort.pay` is reached.
  - Requires cashu-vault 0.9.0 (additive `insertOrClaimForSaga` primitive).

### Added

- New client-facing error code `proofs_not_bound` (messages.properties).
- Metric `cashu_mint_melt_proofs_not_bound_total`.
- `MeltSagaProofsNotBoundIT` (4 cases: partial / zero / vault-exception bind
  all abort before payment; happy path proves bind-before-pay via the saga
  ledger) plus 3 new `MeltSagaStateMachineTest` unit cases.

### Deprecated

- `ProofVaultService.markPendingForSaga` — superseded by
  `insertOrClaimForSaga`. Retained as the underlying primitive used by the
  saga reconciler.

### Changed

- Updated cashu-vault 0.8.2 → 0.9.0.

---

## [0.17.0] - 2026-05-24

### Added

- **Spec 003 — Voucher Quote Durability and Funding-Source Binding** (FR-001
  through FR-013). Every voucher proof is now traceable to a durable
  `voucher_funding` row; the "skip payment check" loophole is closed.
  - Four new PostgreSQL tables in `cashu-mint-jpa`: `voucher_funding`
    (Envers-audited, JOINED-inheritance parent of `customer_payment_funding`
    / `merchant_debit_funding` / `merchant_iou_funding`), `voucher_quote`
    (Envers-audited, CAS-transitioned `UNFUNDED → FUNDED → ISSUING →
    ISSUED`), `voucher_issuance` (append-only ledger), and
    `voucher_idempotency_key` (DB-backed `Idempotency-Key` cache).
  - 8 protocol ports in `cashu-mint-protocol/.../proto/` for the voucher
    domain plus `VoucherFundingResolver` strategy + default impl that scans
    `webhook_event` for an `accepted` event and lazily creates a
    `CustomerPaymentFunding` row (idempotent on
    `(provider, provider_event_id)`).
  - `VoucherMintQuoteTask` persists a `voucher_quote` row at quote-creation
    time. `MintTask`'s voucher branch loads the durable record, runs the
    resolver, rejects with `funding_required` when no funding row resolves,
    then CAS-advances through `FUNDED → ISSUING → ISSUED` after signing.
  - `VoucherQuoteRegistry` demoted to a read-through cache; the durable
    repository is the source of truth.
  - REST hardening: `/v1/vouchers/**` now requires `ADMIN` role (FR-007),
    per-principal Caffeine rate-limit + 429 + `Retry-After` +
    `cashu_mint_voucher_rate_limit_breach_total` counter (FR-008),
    DB-backed idempotency replay + 409 tamper detection (FR-009), and a
    scheduled TTL prune for the idempotency cache.
  - Operator queries embedded as Javadoc on `VoucherIssuanceJpaRepository`
    (SC-001 orphan-issuance query + IOU liability dashboard).
- **Spec 004 — Voucher Data Minimisation and Customer-Identity Custody**
  (FR-001 through FR-019, plus Constitution Principle VII ratification
  `1.1.0 → 1.2.0`). Closes the data-custody gap spec 003 introduced — the
  mint no longer stores raw customer / merchant npubs.
  - **Hash at rest**: HMAC-SHA-256 via `javax.crypto.Mac`; salt from env
    `CASHU_MINT_VOUCHER_IDENTITY_SALT` (≥ 32 bytes; boot fails closed
    otherwise). `IdentityHashConverter` applied via `@Convert` on every
    identity column on `voucher_quote`, `customer_payment_funding`,
    `merchant_debit_funding`, and `merchant_iou_funding`.
  - **Anonymous purchases**: `customer_id` nullable; null short-circuits
    the hasher so no enumerable hash-of-empty placeholder appears.
  - **Boot-time backfill**: `VoucherIdentityBackfillService` paginated
    (1000-row chunks, configurable), idempotent via
    `customer_id !~ '^[0-9a-f]{64}$'` filter. Updates live + Envers
    `_aud` rows in one transaction via `VoucherIdentityBackfillBatch`.
    `VoucherBackfillHealthIndicator` keeps `/actuator/health/readiness`
    DOWN until every required table has `completed_at IS NOT NULL`.
  - **Retention purge**: `VoucherIdentityRetentionPurgeService` daily
    `@Scheduled` (cron from `cashu.mint.voucher.identity-purge-cron`,
    default `0 0 3 * * *`). Nullifies identity columns on terminal-state
    rows past the retention boundary (default 90 days). Records audit row
    in `voucher_quote_purge_log` for the "purged" vs "anonymous"
    distinction.
  - **Idempotency cache scrub**: `IdentityFieldScrubber` walks JSON
    response bodies before they hit `voucher_idempotency_key.
    response_body_json`; hashes identity field values so a DB dump of
    the cache contains zero raw npubs.
  - **Forensic lookup** (FR-009): `POST /admin/voucher/forensic/
    customer-purchases` + `/merchant-purchases`. Operator submits raw
    npub; mint hashes internally; returns matching voucher_quotes. Salt
    never leaves the mint.
  - **Three new Grafana dashboards**: `voucher-liability-overview`,
    `voucher-token-integrity` (orphan-issuance gauge — SC-001 made
    glanceable), `voucher-iou-liability`. All run via a dedicated
    `cashu_mint_grafana_ro` PostgreSQL role with **column-level
    `GRANT SELECT` that excludes `customer_id` + `merchant_id`** —
    defence in depth on top of dashboard JSON review.
    `GrafanaRolePermissionIT` proves the DB-layer enforcement.
  - Customer-facing disclosure document at `docs/explanations/voucher-
    data-record.md`; CI test `DisclosureDocSchemaContractTest` fails the
    build on schema-vs-doc drift.

### Changed

- Bumped `cashu-mint` aggregator and all internal modules from `0.16.0`
  to `0.17.0`. No breaking API changes; additive only.
- `MintIntegrityContext` service-locator gained `.installVoucher()` +
  `.identityHasher()` accessors, extending the pattern from specs
  001/002/003 to the spec-004 surface.

### Security

- Customer + merchant npubs are now stored as HMAC-SHA-256 digests on
  every voucher-related table. A DB dump no longer reveals raw
  identifiers. Salt rotation in v1 is forward-only and explicitly
  accepts loss of pre-rotation forensic-lookup capability (see
  `specs/004-voucher-data-minimisation/quickstart.md` § 7).
- Grafana DB role `cashu_mint_grafana_ro` is provisioned with
  column-level grants that EXCLUDE identity columns — an ad-hoc
  Grafana query against `customer_id` fails at the PostgreSQL layer
  with `permission denied for column customer_id`.

### Fixed (PR #324 review round)

- `IdentityHashConverter` is now idempotent on already-hashed values
  (regex `^[0-9a-f]{64}$`). A JPA load → merge no longer double-hashes
  the stored HMAC, which would have broken every forensic lookup.
- `VoucherIdentityBackfillService` injects `IdentityHasher` directly
  instead of pulling from the static `MintIntegrityContext` at
  `@PostConstruct`; removes init-order race that could silently no-op
  the backfill.
- Per-batch backfill execution extracted to `VoucherIdentityBackfillBatch`
  so the `@Transactional` boundary is honoured by the Spring proxy
  (live + `_aud` UPDATEs commit together per research R9).
- `VoucherIdentityRetentionPurgeService` audit-table UPDATE now scopes
  by the live row's `lifecycle_state`; previously could purge `_aud`
  revisions of still-active UNFUNDED / FUNDED rows.
- Grafana datasource + Flyway placeholder now read the same env var
  (`CASHU_MINT_GRAFANA_RO_PASSWORD`) — previously the two diverged and
  silently broke dashboards.
- Prometheus backward-compat aliases switched from
  `metric_relabel_configs` (which REWROTE singular → plural and dropped
  the singular) to recording rules in `alerts.yml` (which CREATE the
  plural alongside the live singular).
- Alertmanager spec=004 routes moved BEFORE the severity catch-alls so
  the first-match-wins router actually reaches the voucher-pagerduty /
  voucher-slack-* receivers.
- `quickstart.md` § 7: replaced fictional `rotate-identity-salt`
  command with the actual v1 forward-only rotation runbook.

### Migration notes

- The new voucher / data-minimisation behaviour is gated on
  `cashu.mint.jpa.enabled=true`. Existing deployments are unaffected
  until the flag is flipped.
- When flipping the flag, populate the new env vars per
  `specs/004-voucher-data-minimisation/quickstart.md` § 1–2: at minimum
  `CASHU_MINT_VOUCHER_IDENTITY_SALT` (≥ 32 bytes) and
  `CASHU_MINT_GRAFANA_RO_PASSWORD`. Boot fails closed when the salt is
  unset or too short.
- First boot after flag flip runs the identity backfill (idempotent,
  paginated). `/actuator/health/readiness` stays DOWN until every
  identity column completes; mint stays out of the load balancer
  rotation during that window.

---

## [0.16.0] - 2026-05-23

### Added

- **Spec 001 — Mint Quote Amount Binding and Webhook Integrity** (NUT-04 /
  NUT-19 / FR-001 through FR-014). All P1 + P2 user stories closed. New
  behaviour is opt-in behind `cashu.mint.jpa.enabled=true` (defaults to
  `false`); existing deployments are unaffected until the flag is flipped.
- New `cashu-mint-jpa` Maven module with three append-only / audit-tracked
  tables in PostgreSQL: `mint_quote` (Envers-audited), `issuance_record`,
  and `webhook_event`. Ships its own Flyway migrations under
  `db/migration/spec001/` and an opt-in Spring autoconfig
  (`MintJpaAutoConfiguration`) that provides DataSource, EntityManagerFactory,
  and TransactionManager beans from `cashu.mint.jpa.datasource.*` properties.
- US1: `MintTask` now binds every NUT-04 issuance to the durable quote
  amount (FR-001), CASes `PAID → ISSUING → ISSUED` (FR-002 / FR-011),
  cross-checks the gateway via `Gateway.getAmount(quoteId)` (FR-010), and
  emits `cashu_mint_amount_mismatch_total{path="mint"}` /
  `cashu_mint_quote_cross_check_failures_total{path="mint"}` counters.
- US2: `QuoteStatusUpdater.record(...)` rewrite — webhook `PENDING → PAID`
  transitions now require matching `(amount, unit, payment_method)` and a
  previously-unseen `(provider, provider_event_id)` (FR-005 / FR-006). Each
  delivery persists exactly one append-only `webhook_event` row with the
  resolved outcome (`accepted` / `amount_mismatch` / `unit_mismatch` /
  `method_mismatch` / `duplicate` / `tamper` / `expired` / `noop` /
  `orphan` / `unsigned_rejected` / `signature_invalid`, FR-008).
  `cashu_mint_webhook_event_total{outcome=...}` counter emitted on every
  classification.
- US3: NUT-19 idempotent replay — a retry against an `ISSUED` quote with
  the same blinded outputs returns the previously signed promises; with
  different outputs, `quote_already_issued`. Bounded-backoff polling
  (50/100/200/400/800 ms) when a concurrent writer is mid-`ISSUING`.
  `cashu_mint_idempotent_replay_total{path="mint"}` counter on every replay.
- FR-007 — `WebhookSecretStartupValidator` fails the Spring context in any
  non-`local` profile when `cashu.mint.webhook.shared-secret` is unset.
  Existing `webhook.secret` continues to work; the canonical key going
  forward is `cashu.mint.webhook.shared-secret`.
- FR-009 — `LongArithmeticArchTest` (ArchUnit) fails the build if any
  amount-bearing field in `cashu-mint-protocol`'s `tasks/` or `ports/`
  packages is typed `int`/`Integer` instead of `long`. Caught and widened
  one existing regression (`MintQuoteTask.amount`).
- New port interfaces in `cashu-mint-protocol/.../proto/ports/`:
  `MintQuote` / `MintQuoteRepository` / `IssuanceRecord` /
  `IssuanceRecordRepository` / `WebhookEvent` / `WebhookEventRepository` /
  `ProviderIdentifier` / `MintIntegrityContext`.
- `OutputsHash` utility — SHA-256 over sorted `(amount, keyset_id, B_)`
  tuples — backs the NUT-19 idempotent-replay equality check.
- New docs: `docs/how-to/configure-webhook-integrity.md` covers the
  mandatory-secret contract, provider key, feature flag, outcome → HTTP
  status table, and operator reconciliation SQL.
- `NutAdvertisementContractTest` pins the advertised NUT set in
  `mint.yaml` against a documented test-backed allow list
  ({4, 5, 7, 8, 9, 10, 11, 12, 17}); the test fails if a NUT is added or
  removed without updating the inventory.
- `MintQuoteJpaRepository` class-level Javadoc ships two operator
  reconciliation queries (FR-001 + FR-005 daily invariants).

### Changed

- Webhook idempotency is now durably keyed by
  `(provider, provider_event_id)` per FR-006.
  `PaymentNotification#getIdempotencyKey()` (the legacy `paymentMethod:quoteId`
  cache key) is `@Deprecated`. The cache-only path is retained as a
  fallback when the durable repositories are absent (unit-test contexts).
- `QuoteStatusUpdater` Caffeine cache is now a read-through accelerator
  only; write decisions go through the durable repositories when wired.
- `WebhookSignatureValidator` removed the silent skip-if-blank branch
  (FR-007). A missing secret now fails validation rather than passing
  through.
- `cashu-mint-admin` modules aligned to 0.16.0 to catch up the stale
  0.14.2 parent reference that lingered after the 0.15.0 bump.

### Fixed

- `MintJpaAutoConfiguration` now wires its own `DataSource`,
  `EntityManagerFactory`, `JpaTransactionManager`, and Flyway runner —
  earlier scaffolding declared `@EnableJpaRepositories` without a
  DataSource bean, so the `cashu.mint.jpa.enabled=true` path was dead
  code that failed at context startup.
- `MintQuoteJpaRepository#casLifecycle` is now `@Transactional` —
  earlier the modifying JPQL CAS threw
  `InvalidDataAccessApiUsageException` when called from non-transactional
  code paths.
- `QuoteStatusUpdater#record` is now `@Transactional` to wrap the
  combined `webhook_event` insert + `mint_quote` CAS.
- Spec 001 Flyway migrations moved to `db/migration/spec001/` to avoid
  V1 collisions with `payment-adapter-model` and `cashu-vault-jpa` on the
  classpath.
- All hash columns (`request_hash`, `outputs_hash`, `signature_digest`)
  switched from `CHAR(64)` to `VARCHAR(64)` so Hibernate's schema validator
  doesn't reject the mapping.
- `flyway-database-postgresql` pinned in `dependencyManagement` to match
  `${flyway.version}` (11.2.0); Spring Boot's transitive resolution was
  pulling 11.7.2 and crashing the Flyway runner.

### Tests

- `cashu-mint-protocol`: 171 → 190 cases (+19) — `OutputsHashTest`,
  `MintTaskAmountValidationTest`, `IssuingConcurrencyTest`,
  `LongArithmeticArchTest`, `NutAdvertisementContractTest`, plus
  extended `MintQuoteTaskTest`.
- `cashu-mint-webhook`: 38 → 49 cases (+11) — `QuoteStatusUpdaterDurableTest`
  exercises the full outcome matrix with mocked durable repositories.
- `cashu-mint-rest-it`: 5 new spec-001 IT classes (18 cases) under
  Testcontainers Postgres 16-alpine — `FlywayMigrationIT`,
  `WebhookSignatureBootIT`, `WebhookAmountBindingIT`,
  `MintQuoteAmountBindingIT`, `MintQuoteConcurrencyIT`.

---

## [0.15.0] - 2026-05-22

### Added

- `/v1/info` now advertises NUT-11 (P2PK spending conditions). The mint
  already verified P2PK secrets at the wire (`P2PKSpendingCondition` wired
  into `VerifyProofsTask:128`) but the info response silently omitted the
  entry, so clients that gate P2PK use on `/v1/info` — including
  `imani-gateway-core`'s spec-029 in-person delivery saga branch — would
  refuse the path despite swap requests working. The new entry is a simple
  `supported: true` (no per-method config), matching NUT-09 / NUT-10 / NUT-12.

---

## [0.14.2] - 2026-05-12

### Fixed

- `CheckStateTask` (NUT-07 `/v1/checkstate`) was passing the already-computed hash-to-curve point `Y` back through `DefaultProofVaultService.retrieveProof()`, which calls `SecretUtil.toYFromString` (= `hash_to_curve`) again. The second hash produced a point that no stored proof was keyed under, so every checkstate response was a false `UNSPENT` regardless of the proof's actual state. Wallets relying on NUT-07 as a pre-spend oracle were flying blind, only learning that proofs were SPENT/PENDING when the subsequent swap rejected them.
- `VoucherSpendingCondition.verify()` rejected proofs with `verify_proof_already_used_error` for *any* existing `ProofEntity`, not just terminal `STATE_SPENT`. This blocked legitimate saga retries when the same proofs were still in `STATE_PENDING` from a prior in-flight attempt. The condition now only rejects on `STATE_SPENT`, mirroring `InvalidateProofsTask.storeAndInvalidateIdempotent()`'s idempotent recovery path.
- `RSSSpendingCondition.verify()` had the same PENDING-treated-as-terminal bug; same fix applied.

### Added

- `ProofVaultService.retrieveProofByY(yHex)` — new lookup method for callers that already hold the hash-to-curve point Y (NUT-07 wire shape). Skips the hash step that `retrieveProof(secret)` applies for raw-secret callers. `CheckStateTask` now uses this method.
- `DefaultProofVaultServiceTest` — regression coverage proving `SecretUtil.toYFromString` is not idempotent on Y, and pinning the contract for both lookup methods.

---

## [0.14.1] - 2026-02-18

### Added

- HashiCorp Vault service and initialization scripts to `docker-compose.dev.yml` and `docker-compose.prod.yml`
- Admin module: user guide tutorial and transactional outbox explanation documentation

### Changed

- Updated `payment-adapter` dependency to 0.10.0
- Updated `cashu-vault` dependency to 0.7.0

### Fixed

- Admin module: made lifecycle history append idempotent for outbox at-least-once redelivery
- Admin module: added missing Flyway migration scripts and made outbox handler fail-fast
- Admin module: corrected test migration paths to `db/migration/admin/`
- Protocol: resolved DLEQ non-determinism and duplicate storage key issues in tests

---

## [0.14.0] - 2026-02-17

### Added

- `CONTRIBUTING.md` contributor guide with branch naming, commit conventions, and PR process
- `docs/reference/glossary.md` — Cashu and ecash terminology (~35 terms)
- `docs/reference/error-codes.md` — complete REST API error code reference with causes and resolutions
- `docs/how-to/deploy-production.md` — production deployment checklist (TLS, secrets, monitoring, backups)
- `docs/how-to/develop-gateway-adapter.md` — guide for implementing custom payment gateway adapters
- `docs/tutorials/websocket-client-example.md` — step-by-step WebSocket client tutorial with wscat and JavaScript
- `docs/how-to/troubleshoot-common-issues.md` — consolidated troubleshooting guide
- `docs/explanations/virtual-thread-adoption.md` — consolidated virtual thread adoption narrative
- Admin module: vault provisioning saga with compensation logic
- Admin module: active mint checks by unit during lifecycle create and resume operations
- Admin module: integration and E2E tests for vault provisioning saga

### Changed

- Expanded `docs/reference/rest-api.md` with WebSocket endpoint documentation and error response section
- Expanded `docs/reference/nuts.md` from link list to table with implementation classes and status
- Added NUT-12 (DLEQ proofs) and NUT-17 (WebSocket subscriptions) to `docs/explanations/architecture-and-nuts.md`
- Merged `voucher-mint-quote-overview.md` TL;DR into `voucher-mint-quote-percentage.md`
- Updated `README.md` with admin module section, contributing link, and Diataxis documentation index
- Updated `TESTING.md` troubleshooting section to cross-reference new troubleshooting guide
- Archived `docs/loom/` files to `docs/archive/loom/` with header notes pointing to consolidated doc
- Admin module: updated Flyway migration location and Jackson serialization settings
- Admin module: enhanced error handling and configuration
- Updated `mint-admin-web` vite config to use environment variable for API proxy target
- Added Byte Buddy dependency and excluded `slf4j-simple` from `cashu-lib-common`
- Converted `cashu-mint-admin` from git submodule to regular directory

### Removed

- Deleted `docs/explanations/voucher-mint-quote-overview.md` (merged into percentage doc)
- Removed `docs/loom/` directory (archived to `docs/archive/loom/`)

---

## [0.13.0] - 2026-02-16

### Added

- Integrated `cashu-mint-admin` as a git submodule with CLI, REST API, web UI, and test modules
- Added `mint-admin-web` and `mint-admin-tests` to the Maven reactor
- New documentation: getting started tutorial, architecture overview, NUT implementation guide, E2E test guide, environment variables reference
- Admin module documentation: CLI command reference, REST API reference, configuration reference, architecture explanation

### Changed

- Enhanced error handling and updated database configuration for PostgreSQL
- Overhauled `docs/` directory — expanded skeleton files, removed misplaced content, consolidated trivial docs
- Updated CLAUDE.md docs file count from 21 to 32

### Removed

- Removed misplaced imani-bridge files (`SECURE_CODING.md`, `security-implementation-plan.md`)
- Removed unfilled `baseline-metrics.md` template
- Consolidated `java-version.md`, `license.md`, and `disclaimer.md` into parent docs

---

## [0.12.2] - 2026-02-03

### Changed

- Updated `cashu-vault` dependency to 0.6.0

### Added

- Added security section to README documenting Oracle Java Secure Coding Guidelines compliance

---

## [0.12.1] - 2026-02-02

### Changed

- **QuoteStatusUpdater**: Migrated from count-based to weight-based eviction using `maximumWeight()` and custom weigher for accurate memory management
- **QuoteStatusUpdater**: Added `recordStats()` and Micrometer metrics integration (`CaffeineCacheMetrics.monitor()`) for cache observability
- **VoucherQuoteRegistry**: Replaced unbounded `ConcurrentHashMap` with Caffeine cache (24h TTL, 10k max entries) to prevent memory leaks

### Improved

- **Collection Capacity Optimization**: Added initial capacity to HashMap/ArrayList constructors across protocol tasks to reduce resizing overhead:
  - `MintProtocolUtil.createLightningAddressRequest()`: HashMap capacity 3
  - `MintTask.execute()`: HashMap capacity 4
  - `SwapTask.execute()`: ArrayList capacity matching input size
  - `RestoreSignaturesTask`: ArrayList capacities for outputs/signatures
  - `P2PKSpendingCondition`: Estimated ArrayList capacity
- **SwapTask**: Optimized double stream iteration to single pass for voucher proof detection

### Fixed

- Removed unused import for `DBMintVault` in `MintProtocolUtil`
- Added `results/` directory to `.gitignore`

---

## [0.12.0] - 2026-02-02

### Security

- Completed Oracle Java Secure Coding Guidelines remediation tasks: final NUT/utility classes with private constructors, SHA-256 key derivation for lock keys, unmodifiable MintInfo nuts map, sanitized exception messages, webhook input validation, configurable swap/mint limits, WebSocket subscription limits, and NUT security Javadoc

### Changed

- Moved the Java secure coding audit report into the `audits/` directory

### Fixed

- Aligned `VerifyProofsTaskTest` voucher secret FQCN assertion with the current `nut18` package

---

## [0.11.1] - 2026-01-31

### Fixed

- **NUT-17 Info Endpoint**: Fixed NUT-17 WebSocket configuration not appearing in `/v1/info` response
  - Spring's `@ConfigurationProperties` binding could not correctly handle complex nested list structures bound to `Object` type fields
  - Added direct YAML loading via SnakeYAML for NUT-17 configuration
  - Controller now explicitly serializes nuts map using getter to ensure dynamically loaded NUT-17 is included
  - Added unit test to verify NUT-17 support in mint info

---

## [0.11.0] - 2026-01-28

### Added

- **NUT-17 WebSocket Subscriptions**: Real-time notifications for proof and quote state changes
  - WebSocket endpoint at `/v1/ws` with JSON-RPC 2.0 protocol
  - `SubscriptionManager` for session tracking and efficient pub/sub with indexing
  - `Nut17WebSocketHandler` for JSON-RPC message handling (subscribe/unsubscribe)
  - `ProofStatePublisher` for broadcasting proof state transitions (UNSPENT → PENDING → SPENT)
  - `QuoteStatePublisher` for broadcasting quote state transitions (UNPAID → PAID → ISSUED)
  - `Nut17EventPublisher` service for publishing events from controllers
  - Spring ApplicationEvent integration for decoupled event propagation
  - Configurable via `cashu.websocket.enabled` property
  - Security warning logged at startup when wildcard origins (`*`) used in production

- **NUT-17 Protocol Implementation**: `NUT17.java` static utility class
  - Subscription parameter validation
  - Subscription ID generation
  - JSON-RPC response and notification factory methods
  - Filter ID extraction utilities

- **NUT-17 Integration Tests**: `Nut17WebSocketIT` with comprehensive test coverage
  - WebSocket connection establishment
  - Subscribe/unsubscribe command handling
  - Current state notification on subscription
  - Proof state change notifications
  - Multiple subscriber notification delivery

- **NUT-17 Unit Tests**: Event publisher test coverage
  - `ProofStatePublisherTest` for proof state event handling
  - `QuoteStatePublisherTest` for quote state event handling
  - `Nut17EventPublisherTest` for event emission via ApplicationEventPublisher

- **Virtual Thread Guidelines**: Comprehensive `CLAUDE.md` documentation
  - Decision table for when to use Virtual Threads
  - Code patterns for parallel I/O with CompletableFuture
  - Anti-patterns to avoid (synchronized on I/O, platform thread pools)
  - Configuration reference for VT-related components

### Changed

- Updated `mint.yaml` to advertise NUT-17 WebSocket subscription support
- Updated cashu-lib dependency from 0.13.1 to 0.14.0 (includes NUT-17 DTOs)
- Updated cashu-wallet dependency from 0.6.1 to 0.6.3
- `SubscriptionManager.sendCurrentState()` now uses Virtual Threads for parallel I/O
  - Parallel vault queries for proof state lookups
  - Parallel gateway queries for mint/melt quote state lookups
  - Improves performance when subscribers watch multiple items
- `WebSocketConfig` allowed-origins split now handles whitespace around commas
- Removed redundant `@ConditionalOnProperty` from `WebSocketConfig` bean method
- NUT-17 quote state payloads now enriched with additional fields from quote lookups
  - Mint quote notifications include `amount`, `request`, and `expiry`
  - Melt quote notifications include `amount` and `expiry`

### Fixed

- Race condition in `SubscriptionManager.subscribe()` using atomic `compute()` operation

---

## [0.10.2] - 2026-01-28

### Added

- Request tracing and logging for `/swap` endpoint with correlation IDs for easier debugging
- Idempotent proof handling in `InvalidateProofsTask` to safely handle duplicate invalidation requests

### Changed

- Updated cashu-lib dependency from 0.13.0 to 0.13.1

---

## [0.10.1] - 2026-01-26

### Changed

- Aligned all child module versions to 0.10.1 (previously at 0.9.0)
- Updated cashu-vault dependency to 0.5.0

---

## [0.10.0] - 2026-01-26

### Added

- **Proof Locking for Double-Spend Prevention**: `SwapTask` now uses per-proof locking via `ProofLockManager`
  - Serializes concurrent swap requests for the same proofs
  - Prevents double-spend attacks at the application level
  - Allows parallel swapping of different proof sets
  - Complements database-level unique constraints in cashu-vault

- **Concurrency Tests for SwapTask**: Comprehensive test suite in `SwapTaskConcurrencyTest`
  - Tests serialization of same-proof swaps
  - Tests parallel execution of different-proof swaps
  - Tests double-spend prevention behavior
  - Tests partial overlap handling

### Changed

- Updated nostr-java dependency from 1.2.1 to 1.3.0
- Updated cashu-lib dependency from 0.12.0 to 0.13.0
- Updated cashu-vault dependency from 0.4.6 to 0.5.0

### Fixed

- **Webhook Cache TTL**: Added TTL-based cache eviction to `QuoteStatusUpdater`
  - Prevents unbounded memory growth from completed payments
  - Configurable via `webhook.cache.ttl` property

---

## [0.9.0] - 2026-01-25

### Added

- **Webhook-Based Payment Notifications**: New `cashu-mint-webhook` module for push-based payment status
  - `PaymentWebhookController` receives payment events at `/webhook/payment`
  - `QuoteStatusUpdater` maintains in-memory cache for instant payment lookups
  - `WebhookSignatureValidator` validates HMAC-SHA256 signatures from `X-Webhook-Signature` header
  - `PaymentNotification` DTO with idempotency key generation for deduplication
  - Health endpoint at `/webhook/health` with cache statistics

- **PaymentStatusChecker Interface**: New abstraction in `cashu-mint-protocol` for payment verification
  - `isPaid(quoteId)` for instant cache lookup
  - `getPreimage(quoteId)` to retrieve payment proof
  - `markConsumed(quoteId)` for cleanup after successful minting
  - `MintTask` checks webhook cache first, falls back to gateway polling

- **Comprehensive Webhook Tests**: Unit and integration test coverage
  - `PaymentWebhookControllerTest`, `QuoteStatusUpdaterTest`, `WebhookSignatureValidatorTest`
  - `PaymentWebhookIT`, `PaymentWebhookE2EIT` for end-to-end testing

- **Payment Webhook Documentation**: `docs/explanations/payment-webhook-architecture.md`
  - Architecture comparison (polling vs push-based)
  - Configuration and benefits

### Changed

- `MintTask` now checks `PaymentStatusChecker` before polling gateway, reducing latency for cached payments
- `cashu-mint-rest` depends on `cashu-mint-webhook` module

---

## [0.8.0] - 2026-01-23

### Added

- **Virtual Thread Support (Project Loom)**: Full implementation for Java 21+ runtime optimization
  - Enable via `spring.threads.virtual.enabled=true` (default) or `SPRING_THREADS_VIRTUAL_ENABLED` env var
  - Virtual threads handle all request processing and async tasks
  - Per-quote locking with `QuoteLockManager` for parallel quote processing
  - Per-proof locking with `ProofLockManager` for parallel melt operations
  - Double-mint detection in `DefaultSignatureVaultService` as safety net

- **Lock Observability**: Prometheus metrics for lock contention monitoring
  - `cashu_mint_lock_wait_seconds` - time spent waiting to acquire locks
  - `cashu_mint_lock_hold_seconds` - time spent holding locks
  - `cashu_mint_lock_active` - current number of held locks
  - `LockMetrics`, `MicrometerLockMetricsAdapter` in observability module

- **Virtual Threads Grafana Dashboard**: New `cashu-mint-virtual-threads.json` dashboard
  - Lock contention panels (wait time, hold time, active locks)
  - JVM thread metrics (live threads, thread states)
  - Tomcat connection pool monitoring

- **Gateway Client Optimization**: JDK HttpClient with virtual thread executor
  - `GatewayClientConfiguration` provides `gatewayRestTemplate` bean
  - Configurable timeouts: `GATEWAY_CLIENT_CONNECT_TIMEOUT`, `GATEWAY_CLIENT_READ_TIMEOUT`

- **VT Operational Runbook**: `docs/runbooks/virtual-thread-issues.md`
  - Diagnosis and resolution for lock contention, pinning, memory leaks
  - Rollback procedure and escalation path

- **Load Testing Infrastructure**: k6 scripts and baseline metrics capture
  - `scripts/load-test-mint.js` for performance testing
  - Heap exhaustion testing for VT workloads

### Changed

- Updated cashu-lib dependency from 0.11.1 to 0.12.0
- Updated cashu-wallet dependency from 0.4.4 to 0.5.0
- Tomcat thread pool reduced to 50 max threads (VTs handle concurrency)
- Tomcat connection limits set to 2000 max-connections (primary VT concurrency limit)
- `AsyncConfig` configures virtual thread executor for `@Async` tasks

### Deprecated

- `ThreadUtil.MINT_MELT_LOCK` - replaced by `QuoteLockManager.lockQuote()` for per-quote locking

### Fixed

- Voucher arbitrary denominations now work correctly for minting and swaps

---

## [0.7.3] - 2026-01-21

### Changed

- Renamed payment-gateway dependencies to payment-adapter (0.6.0)
- Updated docker-compose service names from gateway to adapter

### Fixed

- Voucher issuance responses now include a generated `cashuA` token so REST clients and Nostr integration tests receive non-null voucher tokens.
- Integration test configuration now uses the token-enriching voucher service to keep Nostr voucher flows aligned with the REST behavior.

---

## [0.7.2] - 2026-01-15

### Changed

- Updated payment-gateway dependency to 0.5.0

---

## [0.7.1] - 2026-01-10

### Changed

- Updated cashu-lib dependency from 0.10.0 to 0.11.1
- Updated cashu-gateway dependency from 0.4.8 to 0.5.0
- Updated cashu-voucher dependency from 0.4.0 to 0.5.0
- Updated cashu-wallet dependency from 0.4.2 to 0.4.4
- Updated cashu-client dependency from 1.2.7 to 1.2.8

---

## [0.7.0] - 2026-01-07

### Added

- **Voucher Tag Support**: Enhanced voucher proof verification with tag-based identification
  - Enables voucher proofs to be identified and processed using NUT-10 tags
  - Supports flexible voucher detection across swap and verification operations

### Changed

- Bumped Spring Boot to 3.5.6, Tomcat to 10.1.48, and Logback to 1.5.19 to pick up upstream security fixes.
- Replaced deprecated Prometheus configuration flags with the current `management.prometheus.metrics.export.enabled` property and documented the new setting.

### Fixed

- Resolved Qodana findings across protocol and REST modules: removed redundant exception handling, enforced non-null blinded messages, improved refund signature logging, hardened YAML property loading, tightened preload SQL path validation, and cleaned up unused variables.
- Added Spring configuration metadata for Phoenixd, webhook, and voucher flags so test property files resolve cleanly.

---

## [0.6.0] - 2026-01-06

### Added

- **VoucherSpendingCondition**: New spending condition for voucher proof verification
  - Uses dynamic key derivation (same as minting) for arbitrary voucher amounts
  - Enables voucher proofs with non-power-of-2 amounts to be verified and swapped
- **Voucher Mock Payment**: Voucher tokens now skip Lightning payment verification during minting
  - Vouchers are merchant IOUs with no real bitcoin backing
  - `VoucherQuoteRegistry.isVoucherQuote()` detects voucher quotes in `MintTask`
  - Audit logging tracks when mock payment is used
- **Mixed Proof Type Validation**: `SwapTask` rejects operations mixing voucher and regular proofs
  - `VoucherSecretDetector.isVoucherSecret()` identifies voucher proofs
  - Clear error message: `mixed_proof_types_error`
- **Arbitrary Voucher Denominations (Free Splitting)**: Vouchers can use any positive amount
  - No power-of-2 denomination constraint for voucher tokens
  - Enables free splitting (e.g., 100 → 33 + 67) without swap overhead
  - `VoucherKeyDerivation` provides HMAC-SHA256 based key derivation for arbitrary amounts
  - `VoucherMasterSecretConfig` configures the voucher master secret
- New unit tests for voucher mock payment behavior:
  - `MintTaskTest`: voucher quote skip payment, regular quote requires payment, arbitrary denominations
  - `SwapTaskTest`: mixed proof rejection, voucher-only swaps, non-power-of-2 splits
- New documentation: `docs/explanations/voucher-mock-payment.md`

### Changed

- `MintTask` now branches on `isVoucherQuote` for payment verification and denomination validation
- `SwapTask` validates proof types before processing and allows arbitrary output amounts for voucher swaps
- `SignBlindedMessageTask` supports voucher mode with dynamic key derivation
- `VerifyProofsTask` routes voucher proofs to `VoucherSpendingCondition` for dynamic key verification

### Fixed

- Voucher proof verification now uses dynamic key derivation matching minting
  - Previously, voucher proofs with arbitrary amounts (e.g., 33 sats) failed verification
  - The vault had no stored key for non-power-of-2 amounts, causing `verify_proof_key_set_not_found`
  - Now uses `VoucherKeyDerivation` to derive keys on-the-fly during verification

---

## [0.5.2] - 2025-12-28

### Changed

- Updated cashu-voucher dependency from 0.3.6 to 0.3.7
- Updated cashu-wallet dependency from 0.4.0 to 0.4.2
- Updated cashu-client dependency from 1.2.6 to 1.2.7

---

## [0.5.1] - 2025-12-23

### Added

- Built-in task instrumentation via `TaskExecutionRecorder`/`InstrumentedTask`, enabling task-level metrics even when protocol tasks are instantiated directly.

### Fixed

- Enforce Model B voucher rejection during swap and melt verification with clear `CashuErrorException` messages and safer melt proof checks.

### Changed

- Test infrastructure: enable Mockito inline mock maker with ByteBuddy agent to support static/constructor mocks in CI-friendly environments.
- Updated cashu-voucher dependency from 0.3.5 to 0.3.6
- Updated cashu-client dependency from 1.2.5 to 1.2.6
- Updated nostr-java dependency from 1.0.1 to 1.1.0

---

## [0.5.0] - 2025-12-22

### Added

- NUT-12 support enabled across the mint, aligning protocol and REST handling with the updated specification.
- Mint now generates and attaches DLEQ proofs to blind signatures with tests covering proof generation and attachment.

### Changed

- Bumped project version to 0.5.0 across all modules.

---

## [0.4.7] - 2025-12-21

### Added

- New explanation page for voucher mint percentage fees overview (`docs/explanations/voucher-mint-quote-overview.md`).

### Changed

- Upgraded project version to 0.4.7 across all modules.
- Moved voucher percentage fee implementation plan to `project/` to keep user-facing docs concise.

### Fixed

- Voucher Nostr integration tests now use a matching BIP-340 public key for the configured private key.
- Mockito inline/agent wiring stabilized to allow static and constructor mocks in tests.

---

## [0.4.3] - 2025-12-17

### Fixed

- **Voucher Swap Support**: Allow voucher proofs (NUT-10 VOUCHER secrets) in swap operations
  - Removed incorrect Model B enforcement from `VerifyProofsTask` - swapping is not redemption
  - Model B enforcement (controlling where vouchers can be redeemed for goods/services) belongs at the merchant/application layer, not the protocol layer
  - Added `VoucherWellKnownSecret` handling in `getSpendingCondition()` using `RSSSpendingCondition` (same BDHKE verification)

- **Proof Storage**: Fixed `MintProtocolUtil.toProofEntity()` to store Y coordinate instead of raw secret string
  - Was storing `proof.getSecret().toString()` which returns full NUT-10 JSON (992+ characters for vouchers)
  - Now uses `SecretUtil.toY()` to store 66-character hex Y coordinate
  - Fixes "value too long for type character varying(255)" database errors

- **VoucherSecretDetector**: Enhanced detection to recognize all voucher secret formats
  - Added check for `VoucherWellKnownSecret` (NUT-10 format from cashu-lib-common)
  - Added check for `WellKnownSecret` with `Kind.VOUCHER`
  - Maintains reflection-based check for optional `VoucherSecret` from cashu-voucher-domain

### Changed

- Updated to cashu-lib 0.7.2 for NUT-10 BDHKE verification fix

---

## [0.4.2] and earlier

See git history for earlier changes.
