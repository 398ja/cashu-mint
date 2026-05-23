# Phase 0 Research: Mint Quote Amount Binding and Webhook Integrity

**Feature**: 001-mint-quote-webhook-integrity
**Date**: 2026-05-22
**Status**: Resolved

Phase 0 resolves the design questions left open by the spec so the
Phase 1 design (data-model, contracts, quickstart) can be authored
without ambiguity. Each item below records the question, the
options considered, the chosen approach, and the rationale.

---

## R1. Where do the new JPA entities and Flyway migrations live?

**Question**: The protocol module (`cashu-mint-protocol`) holds the
NUT task classes. Should the new `MintQuote`, `IssuanceRecord`, and
`WebhookEvent` entities live there, or in a new sibling module?

**Options**:

1. Put JPA + Flyway in `cashu-mint-protocol` directly.
2. Introduce a new `cashu-mint-jpa` module; expose port
   interfaces (`MintQuoteRepository`, etc.) from the protocol
   module; implement them in the JPA module. Spring Boot wires
   the implementation at runtime.

**Decision**: Option 2.

**Rationale**: Constitution III (Clean Architecture) requires that
protocol tasks depend only on domain abstractions and ports —
infrastructure (JPA, Hibernate, SQL) MUST NOT leak inward. This
mirrors the existing cashu-vault split (`cashu-vault-jpa` +
`cashu-vault-api`). Cost: one extra module + one extra pom.xml.
Benefit: future protocol modifications never have to touch
Hibernate annotations or migrations, and Testcontainers tests can
exercise the JPA module independently.

---

## R2. Where do webhook entities live?

**Question**: `cashu-mint-webhook` is its own module. Do
`WebhookEvent` rows live in its own database, in the same database
as `MintQuote`, or in the existing cashu-mint database?

**Options**:

1. Separate database for webhook events.
2. Shared database with mint quote tables.

**Decision**: Option 2 — shared database with mint quote tables.

**Rationale**: FR-005 requires that webhook `PENDING → PAID`
transitions be atomic with the quote lifecycle. A shared database
allows the webhook handler to update `MintQuote.lifecycle_state`
AND insert into `webhook_event` in one PostgreSQL transaction.
Cross-database two-phase commit would otherwise be required and is
neither cheap nor necessary. The existing cashu-mint database is
the natural home; the JPA module from R1 owns both the quote and
webhook tables.

---

## R3. PostgreSQL idiom for compare-and-set lifecycle transitions

**Question**: Per FR-002 / FR-005, lifecycle transitions
(e.g. `PAID → ISSUING → ISSUED`) MUST be atomic and idempotent.
Should we use pessimistic row locking (`SELECT … FOR UPDATE`),
optimistic locking via `@Version`, or compare-and-set via
conditional UPDATE?

**Options**:

1. Pessimistic `SELECT … FOR UPDATE` — explicit row lock.
2. Optimistic locking via JPA `@Version` — Hibernate manages
   the comparison.
3. Conditional UPDATE: `UPDATE mint_quote SET lifecycle_state =
   'ISSUING' WHERE quote_id = ? AND lifecycle_state = 'PAID'`
   with a row-count assertion.

**Decision**: Option 3 (compare-and-set conditional UPDATE) for
the lifecycle transitions, supplemented by Option 2 (`@Version`)
for general entity updates. Pessimistic locking is rejected as
the higher-latency option for the contended-row case.

**Rationale**: Compare-and-set on `lifecycle_state` is the most
direct expression of the FR-002/FR-005 contract: "transition only
if the current state matches the expected prior state". A
row-count of 0 means a concurrent writer already advanced the
state; the caller MUST re-read and either return idempotent
replay (`ISSUED` with matching outputs) or reject
(`ISSUED` with different outputs). The `@Version` column protects
incidental field updates (e.g., `updated_at`); transitions
themselves use the WHERE-clause guard.

---

## R4. Idempotent NUT-19 replay — how is "same outputs" computed?

**Question**: FR-003 requires that a retry against an `ISSUED`
quote with the same blinded outputs returns the previously signed
promises. How do we determine "same outputs"?

**Options**:

1. Store every output `B_` value verbatim in `issuance_record`
   and compare set-equality on each request.
2. Hash the sorted list of output `B_` values into a single
   `outputs_hash` column; compare hashes.
3. Hash the entire (sorted) `BlindedMessage` list including
   `amount` + keyset id + `B_`.

**Decision**: Option 3.

**Rationale**: `amount`, keyset id, and `B_` together fully
identify an output. Sorting by `(amount, keyset_id, B_)` and
hashing with SHA-256 yields a stable 32-byte fingerprint. Storing
only the hash plus the resulting `BlindSignature` list in
`issuance_record` keeps the row small while supporting fast
equality check. The `BlindSignature`s are stored too (so retries
can return them verbatim without re-signing).

Edge case: a NUT-04 client that mutates one output's amount
between attempts MUST be rejected as `quote_already_issued`
(FR-003) — Option 3 catches this because amount is part of the
hash.

---

## R5. Webhook signature secret — startup contract

**Question**: FR-007 requires startup failure in non-local Spring
profiles when the webhook secret is unset. How is "non-local"
defined, and how is failure signalled?

**Options**:

1. Use Spring `@ConditionalOnProperty` with `havingValue =
   false` to disable webhook handling, log a warning at boot.
2. Use a `@Validated` `@ConfigurationProperties` bean with
   `@NotBlank` on the secret; Spring fails the context.
3. Use a `BeanFactoryPostProcessor` that throws on missing
   property.

**Decision**: Option 2.

**Rationale**: Cleanest, idiomatic, and Spring-native. The
"non-local" gate is expressed by annotating the
`@ConfigurationProperties` class with `@Profile("!local")` (or
equivalent). Boot logs the validation failure clearly and exits
with a non-zero status. SC-005 is verified by a startup-failure
integration test in `cashu-mint-rest-it`.

---

## R6. WebhookEvent idempotency key — exact composition

**Question**: FR-006 mandates idempotency keyed by
`(provider, provider_event_id)`. What does `provider` resolve to
for each backend (phoenixd, etc.)?

**Options**:

1. The Java class name of the gateway implementation.
2. A `gateway.name()` accessor returning a stable string
   identifier set per-gateway (e.g. `"phoenixd"`, `"strike"`).
3. The Spring bean name of the gateway.

**Decision**: Option 2.

**Rationale**: Stable across refactors (class renames don't break
the key); explicit (gateway authors choose the identifier);
greppable in production logs. Add `String name();` to the
`Gateway` interface (payment-adapter side) — coordinated change
tracked in payment-adapter spec backlog. For this feature, the
mint uses the gateway's `name()` if available and falls back to
the class simple name otherwise (logged as a warning so the
fallback is visible during the cross-repo migration).

---

## R7. Existing `paymentMethod:quoteId` idempotency key — migration

**Question**: FR-006 replaces the current `paymentMethod:quoteId`
idempotency with `(provider, provider_event_id)`. What happens to
existing rows / in-flight webhooks during deploy?

**Options**:

1. Drop the old key entirely; in-flight retries during deploy
   are accepted under the new key (provider's first delivery
   wins).
2. Compatibility window: accept either key for one release
   cycle; migrate persisted rows on read.
3. Drain provider queues before deploy; deploy clean.

**Decision**: Option 1.

**Rationale**: The old key is in-process / cache only — there's no
persisted state keyed by `paymentMethod:quoteId` that survives
deploy. Webhook delivery is at-least-once from every provider, so
"first delivery wins" is the normal semantics anyway. Operators
should expect a brief window where duplicate deliveries
re-process under the new key; both deliveries would land an
`accepted` event for the first delivery and a `duplicate` outcome
for the second, which is exactly the desired behaviour.

---

## R8. `Gateway.getAmount(quoteId)` cross-check — failure modes

**Question**: FR-010 requires cross-checking the locally-persisted
quote amount against `Gateway.getAmount(quoteId)` on every `PAID`
transition. What if the gateway call fails?

**Options**:

1. Block the transition on gateway failure (fail-closed).
2. Skip the cross-check on gateway failure (best-effort).
3. Fail-closed, but with a circuit breaker + cached "last
   known good" answer for transient failures.

**Decision**: Option 1 (fail-closed).

**Rationale**: Constitution I — operator-visible alerts and
durable financial state. A gateway failure during a `PAID`
transition is a real signal; the alternative (best-effort)
silently allows the transition based on the locally-stored quote
amount, which can be stale if the webhook arrived before the
gateway updated its own state. Operators get an alert and can
investigate. Option 3's circuit breaker is over-engineering for
v1; revisit if cross-check failures become a recurring operator
nuisance.

---

## R9. `int` / `Stream.mapToInt(...)` ban — enforcement

**Question**: FR-009 forbids `int` and `Stream.mapToInt(...)` over
amounts in validation paths. How is this enforced as a CI gate?

**Options**:

1. Manual code review only.
2. Custom Checkstyle / SpotBugs rule that flags `int amount` and
   `mapToInt`/`sumToInt` on Stream<BlindedMessage> or similar.
3. ArchUnit test that asserts no field named `amount` is of type
   `int` in the relevant packages.

**Decision**: Option 3 (ArchUnit) is the most surgical and
already a known idiom in Spring-Boot projects.

**Rationale**: ArchUnit catches the structural property
("financial amount fields use `long`") rather than the syntactic
detail ("the literal `mapToInt` token"). Lower false-positive rate
than Option 2. Manual review still required for new code paths;
the ArchUnit test is the safety net.

---

## R10. Hibernate Envers vs. an explicit audit table

**Question**: The spec mandates an audit trail. Use Envers
(automatic) or a hand-rolled `mint_quote_transition` table?

**Options**:

1. Hibernate Envers — auto-creates `_AUD` tables, mature, used
   in cashu-vault already.
2. Hand-rolled append-only `mint_quote_transition` table.

**Decision**: Option 1 (Envers).

**Rationale**: Envers is already a Constitution-aligned audit
mechanism (cashu-vault Constitution II). Reuse over re-invention.
The handful of NUT-19 idempotency / retry queries the system needs
are served by `IssuanceRecord` (a separate, hand-rolled
append-only table — see data-model). Envers handles the
`mint_quote.lifecycle_state` timeline as a free side effect.

---

## Open items deferred to Phase 2 (/speckit.tasks)

- Naming and ordering of Flyway migration files (will follow the
  existing `V<timestamp>_<seq>__<description>.sql` convention).
- Exact `application-staging.yaml` / `application-prod.yaml` keys
  for the webhook secret (the `@ConfigurationProperties` bean
  determines this).
- The cross-repo `Gateway.name()` change in payment-adapter is a
  prerequisite for R6's preferred contract; until that lands,
  the mint falls back to the class simple name and logs.

No NEEDS CLARIFICATION items remain in the Technical Context.
