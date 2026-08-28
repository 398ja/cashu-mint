<!--
  Sync Impact Report
  ==================
  Version change: 0.0.0 (template) -> 1.0.0 -> 1.1.0 -> 1.2.0-draft -> 1.2.0
  Modified principles:
    - 1.2.0: Principle VII (Data Minimisation and Customer-Identity
      Custody) RATIFIED. Spec 004's plan + tasks + implementation
      (T010) demonstrate the principle in action. Promoted from
      DRAFT to canonical; now a blocking gate for any spec that
      touches customer or merchant identity in durable storage.
    - 1.2.0-draft: Principle VII added as DRAFT alongside spec 004.
    - 1.1.0: Principle II (Protocol Compliance) expanded with
      explicit upstream spec links to github.com/cashubtc/nuts,
      per-NUT URLs (NUT-00 through NUT-24), and a requirement
      that each task class carry a Javadoc link pinned to a
      specific commit hash. Added an upstream-amendment tracking
      rule (follow-up issue within one release cycle).
  Added sections (1.0.0):
    - 6 Core Principles (Token Integrity, Protocol Compliance,
      Clean Architecture, Testing Discipline, Virtual Threads,
      Secure Coding & Code Quality)
    - Security Requirements
    - Development Workflow
    - Governance
  Removed sections: None (template placeholders replaced in 1.0.0)
  Adapted from imani-merchant constitution (1.0.0):
    - Added Token Integrity (mint-specific existential concern)
    - Added Protocol Compliance (NUT specifications)
    - Removed Stripe-specific clauses (not applicable to mint)
  Templates requiring updates:
    - .specify/templates/plan-template.md — ✅ compatible
    - .specify/templates/spec-template.md — ✅ compatible
    - .specify/templates/tasks-template.md — ✅ compatible
  Follow-up TODOs: None (1.2.0 ratified via spec 004 T010).
-->

# cashu-mint Constitution

## Core Principles

### I. Token Integrity (NON-NEGOTIABLE)

The mint is an authority on the supply of Cashu tokens. Every code
path that creates, destroys, or transfers value MUST preserve the
following invariants:

- **No silent inflation**: the sum of issued blinded outputs MUST
  equal the authorised quote amount for every mint operation; no
  paid quote MUST authorise issuance more than once
- **No silent deflation**: the sum of input proofs MUST cover the
  invoice amount plus the exact fee reserve before any external
  payment is initiated; proofs MUST be marked spent in a durable
  transaction before external value leaves the system
- **Durable financial state**: quote lifecycle, proof state, and
  issuance/melt sagas MUST live in a durable store with atomic
  transitions; in-process maps, caches, and registries MUST NOT
  be the source of truth for write decisions
- **Amount-bound webhooks**: webhook-driven state transitions
  (`PENDING → PAID`, etc.) MUST be conditional on matching amount,
  unit, payment method, and a previously-unseen provider event id
- **`long` arithmetic**: all financial-amount math MUST use `long`
  (or a decimal-safe type); `int` and `Stream.mapToInt(...)` over
  amounts are forbidden in validation paths
- **Operator-visible alerts**: any branch where the mint cannot
  durably account for value movement (e.g., payment-sent-burn-
  failed, payment-unknown) MUST fire an operator alert and refuse
  to auto-retry the external action

Token-integrity violations are blocking defects. Performance,
ergonomics, and refactor cleanliness MUST yield to integrity.

### II. Protocol Compliance (Cashu NUTs)

The mint MUST implement Cashu NUT (Notation, Usage, Terminology)
specifications faithfully and visibly. The authoritative source is
the upstream Cashu protocol repository:

- **Specification index**: [github.com/cashubtc/nuts](https://github.com/cashubtc/nuts)
- **Mandatory NUTs** (every Cashu mint MUST implement):
  - [NUT-00 — Cryptography and Models](https://github.com/cashubtc/nuts/blob/main/00.md)
  - [NUT-01 — Mint public key exchange](https://github.com/cashubtc/nuts/blob/main/01.md)
  - [NUT-02 — Keysets and fees](https://github.com/cashubtc/nuts/blob/main/02.md)
  - [NUT-03 — Swap tokens](https://github.com/cashubtc/nuts/blob/main/03.md)
  - [NUT-04 — Mint tokens](https://github.com/cashubtc/nuts/blob/main/04.md)
  - [NUT-05 — Melt tokens](https://github.com/cashubtc/nuts/blob/main/05.md)
  - [NUT-06 — Mint info](https://github.com/cashubtc/nuts/blob/main/06.md)
- **Optional NUTs** (advertised support implies a passing
  integration test against the linked spec):
  - [NUT-07 — Token state check](https://github.com/cashubtc/nuts/blob/main/07.md)
  - [NUT-08 — Lightning fee return](https://github.com/cashubtc/nuts/blob/main/08.md)
  - [NUT-09 — Restore signatures](https://github.com/cashubtc/nuts/blob/main/09.md)
  - [NUT-10 — Spending conditions](https://github.com/cashubtc/nuts/blob/main/10.md)
  - [NUT-11 — Pay-to-Public-Key (P2PK)](https://github.com/cashubtc/nuts/blob/main/11.md)
  - [NUT-12 — DLEQ proofs](https://github.com/cashubtc/nuts/blob/main/12.md)
  - [NUT-13 — Deterministic secrets](https://github.com/cashubtc/nuts/blob/main/13.md)
  - [NUT-14 — Hashed Time Lock Contracts (HTLCs)](https://github.com/cashubtc/nuts/blob/main/14.md)
  - [NUT-15 — Partial multi-path payments (MPP)](https://github.com/cashubtc/nuts/blob/main/15.md)
  - [NUT-16 — Animated QR codes](https://github.com/cashubtc/nuts/blob/main/16.md)
  - [NUT-17 — WebSocket subscriptions](https://github.com/cashubtc/nuts/blob/main/17.md)
  - [NUT-18 — Payment requests](https://github.com/cashubtc/nuts/blob/main/18.md)
  - [NUT-19 — Cached responses](https://github.com/cashubtc/nuts/blob/main/19.md)
  - [NUT-20 — Signed mint quote](https://github.com/cashubtc/nuts/blob/main/20.md)
  - [NUT-21 — Clear authentication](https://github.com/cashubtc/nuts/blob/main/21.md)
  - [NUT-22 — Blind authentication](https://github.com/cashubtc/nuts/blob/main/22.md)
  - [NUT-23 — Bolt11 payment method](https://github.com/cashubtc/nuts/blob/main/23.md)
  - [NUT-24 — HTTP 402 payment required](https://github.com/cashubtc/nuts/blob/main/24.md)

Compliance rules:

- Each implemented NUT lives in its own task class under
  `cashu-mint-protocol/src/main/java/.../tasks/` named after the
  spec (e.g. `MintTask` → NUT-04, `MeltTask` → NUT-05,
  `SwapTask` → NUT-03, `VerifyProofsTask` → NUT-00 / NUT-07)
- Each task class MUST carry a Javadoc reference to the exact
  spec URL above. When a NUT is revised upstream, the linked URL
  MUST be pinned to a specific commit hash in the Javadoc
- The mint MUST advertise its supported NUTs accurately via the
  NUT-06 info endpoint; advertised support implies a passing
  integration test pinned to the linked spec revision
- Non-standard extensions (vouchers, mock payment, admin endpoints)
  MUST be clearly separated from NUT-compliant paths and MUST NOT
  be reachable on public endpoints without explicit gating; they
  MUST NOT be advertised under the `nuts` key of NUT-06
- Breaking changes to NUT request/response shapes MUST bump the
  mint's MAJOR version
- Upstream NUT amendments MUST be tracked: when
  [cashubtc/nuts](https://github.com/cashubtc/nuts) changes a
  spec the mint claims to support, a follow-up issue MUST be
  filed within one release cycle to either re-test against the
  new revision or drop the advertised support

### III. Clean Architecture

Every module MUST follow Clean Architecture with inward-pointing
dependencies:

- **domain**: Pure entities (proofs, blinded messages, quotes,
  keysets) with zero external dependencies
- **application** / **protocol tasks**: Use cases depending only
  on domain abstractions and gateway/vault ports
- **api**: Ports and DTOs (interfaces only); REST controllers MUST
  delegate to tasks and MUST NOT contain protocol logic
- **infrastructure**: Adapters implementing ports (vault REST
  client, payment-adapter gateway, webhook signature validator)

The Hexagonal (Ports & Adapters), Repository, Factory, and Domain
Event patterns are mandatory. Infrastructure MUST NOT leak into
domain or protocol-task layers. Vault and payment-adapter calls
MUST go through ports owned by the protocol module.

### IV. Testing Discipline

All code MUST meet the following testing standards:

- **Unit tests** (`*Test.java`): Run via `mvn -q test`; every test
  method MUST have a plain-English comment describing the scenario
- **Integration tests** (`*IT.java`, `cashu-mint-rest-it` module):
  Run via the `integration-tests` Maven profile; use Testcontainers
  for PostgreSQL and HTTP test harnesses for the vault and payment
  adapter
- Tests MUST exercise realistic financial scenarios — under-mint,
  over-mint, exact mint, repeated mint, under-melt, exact melt,
  payment-failure-after-burn, webhook amount/unit mismatch — for
  every NUT that moves value
- Coverage gate enforced by `mvn verify`; financial paths MUST be
  covered by both unit and integration tests
- Mocked approximations are forbidden for proof-state and payment-
  status transitions; real vault and real payment-adapter mocks
  (provider-level) MUST be exercised

### V. Virtual Threads for Concurrency

Java 21 Virtual Threads (Project Loom) are the standard concurrency
model for I/O-bound work in the mint:

- Use `Executors.newVirtualThreadPerTaskExecutor()` with
  `CompletableFuture` for parallel I/O (vault calls, payment
  adapter calls, multi-proof verification)
- Use `ReentrantLock` instead of `synchronized` to avoid VT pinning
- Per-proof locks (e.g., `SwapTask`'s lock map) MUST be used for
  serialising state transitions on the same proof
- Spring Boot VT support is enabled via
  `spring.threads.virtual.enabled=true`
- CPU-bound cryptographic operations (BDHKE blinded signing) MAY
  use parallel streams but MUST NOT block VT worker threads on I/O

### VI. Secure Coding & Code Quality

- Input validation at system boundaries (REST controllers, webhook
  endpoints, gateway/vault responses)
- Output encoding to prevent injection in any human-facing surface
  (admin UI, logs)
- Safe cryptography via BouncyCastle; no hand-rolled BDHKE
- Secrets MUST live in environment variables or a secret manager,
  never in code, configs, or commits
- Webhook signatures MUST be verified before any state mutation;
  startup MUST fail in `staging` / `prod` profiles when the
  required shared secret is unset
- OWASP Top 10 vulnerabilities are blocking defects
- Follow SOLID principles; use Java records or Lombok to reduce
  boilerplate
- Prefer unchecked exceptions with context (operation + failure
  type + structured fields)
- YAGNI: no speculative abstractions; three similar lines are
  better than a premature helper

### VII. Data Minimisation and Customer-Identity Custody

> **STATUS**: Ratified in 1.2.0. The reference implementation is the
> voucher identity layer (see `docs/runbooks/voucher-data-minimisation.md`) —
> any new spec that touches customer or merchant identity in
> durable storage MUST follow the same pattern (hash at rest +
> time-bound retention + operator-side salt-aware forensics).

Cashu's non-custodiality is cryptographic — blind signatures
prevent the mint from linking issued proofs to future spends. That
property covers TOKEN custody. It does NOT automatically cover
DATA custody: the mint may still record customer identity at
funding / quote-creation time, and any such record is itself a
custodial liability.

This principle covers the data side:

- **Disclose what is recorded**: if the mint stores customer or
  merchant identity in any durable form, that fact MUST be
  documented in a customer-facing disclosure and linked from the
  surface where the customer initiates the action.
- **Hash identity at rest**: customer / merchant identifiers
  (npubs, principal ids) MUST be stored as salted hashes in
  every durable table including audit shadows. The mint salt
  comes from configuration and MUST NOT be hard-coded.
- **Time-bound retention of PII**: identity-bearing columns MUST
  decay to NULL after a configurable retention window once the
  surrounding record reaches a terminal state. Financial fields
  (amounts, lifecycle, foreign keys) stay; identity does not.
- **Idempotency caches**: response-body caches MUST NOT preserve
  raw identity past the cache TTL. Redact, hash, or scrub.
- **Operator-side recovery**: data minimisation MUST NOT break
  legitimate operator forensics. Operators with the salt MUST be
  able to query in-window data by raw identifier without manual
  hash computation.
- **Salt rotation is a planned operation**: rotation requires a
  documented re-hash batch + downtime window; it is not a casual
  config change.

Token integrity (Principle I) takes precedence: data-minimisation
measures that would weaken the token-integrity invariants are
rejected. The two principles are designed to coexist — see spec
004 for the demonstrated coexistence pattern.

## Security Requirements

- **Authentication on admin paths**: all admin REST endpoints
  (`cashu-mint-admin-rest`) MUST require service authentication
  and MUST NOT be exposed on public networks
- **Authentication on webhook paths**: shared-secret signature
  validation MUST be mandatory in non-local profiles
- **No secrets in commits**: `.env`, credentials, and private keys
  MUST be excluded by `.gitignore` and verified by pre-commit hooks
- **Security event logging**: every rejected request, signature
  failure, amount mismatch, and saga compensation MUST be logged
  with structured fields suitable for SIEM ingestion
- **Dependency vulnerabilities**: critical CVEs in transitive deps
  MUST be addressed within one release cycle; Dependabot/Renovate
  PRs MUST be triaged within the same week
- **Audit trail**: financial state transitions MUST be persisted
  as append-only ledger entries; no UPDATE-in-place on issuance
  or saga records — only new transitions

## Development Workflow

- **Commits**: Conventional Commits format:
  `feat(scope):`, `fix(scope):`, `docs(scope):`, etc. The `scope`
  SHOULD identify the affected module
  (`mint`, `vault-port`, `webhook`, `protocol`, etc.)
- **Builds**: `mvn -q verify` MUST pass before committing
- **Integration tests**: PRs that touch protocol tasks, gateway
  adapters, or webhook handling MUST run with
  `-P integration-tests` locally before merge
- **Versions**: Managed in the parent `pom.xml` properties section
  (`cashu-mint.version` and related `<groupId>.version` properties);
  use `/bumpup` for coordinated bumps across producer/consumer
  pin chains
- **Branching**: Feature branches off `develop`; PRs target
  `develop`; `master` tracks released versions
- **Code review**: All PRs require review; financial-path changes
  MUST be reviewed by a second maintainer with explicit attention
  to the Token Integrity principle

## Governance

This constitution is the authoritative source of project standards
for cashu-mint. It supersedes ad-hoc practices and informal
conventions.

- **Amendments**: Any change to this constitution MUST be
  documented with rationale, reviewed by a maintainer, and
  reflected in the version below. A Sync Impact Report at the top
  of the file MUST summarise the change.
- **Versioning**: MAJOR for principle removals/redefinitions,
  MINOR for new principles or material expansions, PATCH for
  clarifications and wording fixes.
- **Compliance**: All PRs and code reviews MUST verify adherence
  to these principles. Violations of Principle I (Token Integrity)
  are blocking and require maintainer sign-off to merge under any
  exception clause.
- **Runtime guidance**: See `CLAUDE.md` for build commands,
  module structure, and operational patterns (vault persistence,
  gateway adapters, voucher system, virtual-thread usage).

**Version**: 1.1.0 | **Ratified**: 2026-05-22 | **Last Amended**: 2026-05-22
