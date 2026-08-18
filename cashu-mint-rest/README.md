# cashu-mint-rest

REST entry point for the Cashu mint. Hosts the public NUT endpoints
(`/v1/info`, `/v1/keys`, `/v1/keysets`, `/v1/mint/{method}`,
`/v1/melt/{method}`, `/v1/swap`, `/v1/checkstate`, `/v1/restore`,
`/v1/payment/webhook`) plus internal admin endpoints documented below.

## Configuration

Standard mint configuration lives in `src/main/resources/application.properties`
plus the protocol module's `proto.properties`. Spec-001 and spec-002 add
the following properties; all default to safe values for local development:

```properties
# Spec 001 — durable mint quote / webhook integrity (opt-in)
cashu.mint.jpa.enabled=false   # flip to true to activate the JPA path
cashu.mint.webhook.shared-secret=...
cashu.mint.webhook.provider=phoenixd

# Spec 002 — melt saga driver (active when cashu.mint.jpa.enabled=true)
cashu.mint.melt.payment-timeout=PT30S
cashu.mint.melt.reconcile-interval=PT60S
cashu.mint.melt.payment-unknown-ttl=PT1H
cashu.mint.melt.proofs-held-ttl=PT5M

# Spec 003 — voucher quote durability + endpoint hardening
cashu.mint.voucher.iou-policy=DENY                 # ALLOW|DENY; DENY rejects IOU-funded issuance
cashu.mint.voucher.idempotency-key-ttl=PT24H       # voucher_idempotency_key row lifetime
cashu.mint.voucher.rate-limit-tokens-per-minute=60 # per-principal token bucket
cashu.mint.voucher.idempotency-sweep-interval-ms=600000  # TTL sweep interval (10m)
```

## Admin endpoints (internal-only)

The admin endpoints are loaded only when the spec-002 melt saga
infrastructure is wired in (i.e. `cashu.mint.jpa.enabled=true` and the
`MeltSagaRepository` bean is present). They are intended for operator
tooling and reconciliation workflows.

> **Security:** `/admin/**` is protected by Spring Security HTTP Basic
> with role `ADMIN`. Credentials come from
> `cashu.mint.admin.username` (default `admin`) and
> `cashu.mint.admin.password` (no default — set via the
> `MINT_ADMIN_PASSWORD` env var). When the password is unset/blank,
> NO admin user is registered and every request returns 401. Operators
> may supply an encoded password with an explicit prefix
> (e.g. `{bcrypt}$2a$10$...`); plain-text values are wrapped with
> `{noop}` automatically.
>
> A future iteration may replace the in-memory provider with a JWT /
> OIDC service-account integration without changing the
> `SecurityFilterChain` contract.

### `GET /admin/melt-saga/by-id/{meltSagaId}`

Returns the full `MeltSagaResponse` (current state, all amount fields,
provider metadata, full transition timeline) for a given saga.

```bash
curl -u admin:$MINT_ADMIN_PASSWORD \
     http://localhost:7777/admin/melt-saga/by-id/4a3e...
```

Response shape:

```json
{
  "meltSagaId": "...",
  "quoteId": "...",
  "currentState": "COMPLETED",
  "invoiceAmount": 100,
  "exactFeeReserve": 5,
  "assertedFeeReserve": 5,
  "inputAmount": 105,
  "proofCount": 2,
  "paymentHash": "...",
  "provider": "phoenixd",
  "providerEventId": "...",
  "createdAt": "2026-05-23T01:00:00Z",
  "updatedAt": "2026-05-23T01:00:01Z",
  "transitions": [
    { "seq": 1, "fromState": null,         "toState": "PROOFS_HELD",  "actor": "system", "at": "..." },
    { "seq": 2, "fromState": "PROOFS_HELD","toState": "PAYMENT_SENT", "actor": "system", "at": "..." },
    { "seq": 3, "fromState": "PAYMENT_SENT","toState": "COMPLETED",   "actor": "system", "at": "..." }
  ]
}
```

Returns `404 Not Found` if no saga exists with that id.

### `GET /admin/melt-saga/by-quote/{quoteId}`

Same response shape, looked up by the melt quote id rather than the
saga id. Useful for forensic lookups from provider receipts.

### `POST /admin/melt-saga/{meltSagaId}/mark-resolved`

Operator action that appends a transition row recording the resolution
without overwriting `current_state`. Body:

```json
{
  "actor": "operator:alice",
  "reason": "manually reconciled with provider after PAYMENT_SENT_BURN_FAILED"
}
```

The append-only contract is intentional — the spec-002 reconciliation
log preserves every step including operator interventions, so
post-mortems can trace exactly who took what action and when. To
actually flip a saga to a different terminal state, a separate
operator endpoint (TBD) is required; today the resolution is
annotation-only.

### `POST /admin/voucher/forensic/customer-purchases` (spec 004)

Spec 004 FR-009 — operator-facing salt-aware lookup. The operator
submits a raw customer npub; the mint hashes it internally with the
configured `cashu.mint.voucher.identity-salt` and returns matching
voucher quotes (within the 90-day retention window).

```bash
curl -u "$MINT_ADMIN_USER:$MINT_ADMIN_PASSWORD" \
     -H 'Content-Type: application/json' \
     -d '{"customerNpub":"npub1..."}' \
     http://localhost:7777/admin/voucher/forensic/customer-purchases
```

Response:

```json
{
  "customer_hash": "9f8a...e5f3a",
  "match_count": 2,
  "matches": [
    {
      "quote_id": "q-...",
      "face_value": 1000,
      "charged_amount": 100,
      "unit": "sat",
      "lifecycle_state": "ISSUED",
      "funding_id": "f-...",
      "created_at": "2026-04-12T09:15:23Z",
      "updated_at": "2026-04-12T09:15:25Z",
      "retention_state": "in_window"
    }
  ]
}
```

The salt **never leaves the mint**. The operator only sees the hash
their npub mapped to (useful for confirming which row matched).
Post-retention rows are not returned — their identity columns are
NULL so the hash lookup misses them. To distinguish "purged" from
"never happened," operators query `voucher_quote_purge_log` directly.

### `POST /admin/voucher/forensic/merchant-purchases`

Same shape, keyed on `{"merchantNpub":"npub1..."}`.


## Voucher endpoints (`/v1/vouchers/**`) — integrator contract

Spec 003 ships durability + hardening on the voucher mint path. The
contract below applies whenever `cashu.mint.jpa.enabled=true`.

### Customer data disclosure (spec 004 FR-001 + FR-008)

The mint hashes customer + merchant identity at rest and nullifies it
after 90 days. **The customer-facing disclosure document is at
[`docs/explanations/voucher-data-record.md`](../docs/explanations/voucher-data-record.md)** —
every front-end that originates voucher purchases MUST link to it from
the purchase page (per FR-008; cross-repo follow-up tracked in
`imani-apps`).

The schema and the disclosure document are kept in sync via the
`DisclosureDocSchemaContractTest` CI check — drift fails the build.

### Authentication (FR-007)

Every request to `/v1/vouchers/**` requires HTTP Basic auth with the
`ADMIN` role. Same credentials lever as `/admin/**`
(`cashu.mint.admin.{username,password}` / `MINT_ADMIN_PASSWORD` env).
Unauthenticated requests return `401`; authenticated requests without
`ADMIN` return `403`.

> The decomposed-architecture caller is `imani-gateway-atomic`'s
> `AtomicPurchaseController` (port 8083, voucher purchase saga owner),
> which holds the service-account credentials. A future iteration may
> swap the in-memory provider for a merchant-principal JWT verifier;
> the route contract stays the same.

### Idempotency (FR-009)

Every `POST /v1/vouchers/**` request MUST carry an `Idempotency-Key`
header. The natural key in the decomposed architecture is the atomic
saga's `purchaseId`.

| Scenario | Response |
|---|---|
| Missing or blank `Idempotency-Key` header | `400 {"error":"idempotency_key_required"}` |
| Same key + same request hash (replay) | Cached response replayed verbatim |
| Same key + different request hash (tamper) | `409 {"error":"idempotency_key_conflict"}` |

The cache is durable (`voucher_idempotency_key` table, scoped per
authenticated principal) and survives restarts. Default TTL is 24 h
(`cashu.mint.voucher.idempotency-key-ttl`); a scheduled sweeper
prunes expired rows every 10 min by default
(`cashu.mint.voucher.idempotency-sweep-interval-ms`).

### Rate limiting (FR-008)

Per-principal Caffeine token bucket; default capacity 60 / minute
(`cashu.mint.voucher.rate-limit-tokens-per-minute`). On exhaustion:

```
HTTP/1.1 429 Too Many Requests
Retry-After: 60
X-RateLimit-Remaining: 0
Content-Type: application/json

{"error":"rate_limit_exceeded"}
```

The successful path returns `X-RateLimit-Remaining: <n>` so clients
can implement back-pressure.

### Funding gate (FR-002)

A voucher proof is only issued when the durable `voucher_quote` row
has a matching `voucher_funding` row attached. The funding row can
arrive two ways:

1. **Eager** (preferred) — `imani-gateway-atomic` passes a
   `funding_ref` referencing its `escrow_ledger` row at quote-create
   time. (Tracked as a follow-up spec in `imani-gateway-atomic`.)
2. **Lazy fallback** — `VoucherFundingResolverImpl` scans
   `webhook_event` for an `accepted` event matching the
   `quote_id` and creates a `CustomerPaymentFunding` row on demand.
   Idempotent on `(provider, provider_event_id)`.

When neither path resolves a funding row, the mint returns:

```json
{"error": "funding_required"}
```

### IOU policy (FR-006)

`cashu.mint.voucher.iou-policy` gates `MERCHANT_IOU` funding. Default
is `DENY` in every profile; flip to `ALLOW` only when an operator
liability process exists. **Note**: today the policy is enforced at
issuance only — there is no IOU creation REST endpoint that would
allow rejecting at quote-create time. Every IOU issuance fires the
`cashu_mint_voucher_iou_issued_total` counter regardless of policy
so operator dashboards see drift.

### Metrics

| Counter | Meaning |
|---|---|
| `cashu_mint_voucher_issued_total{funding_source=...}` | Successful voucher issuance per funding source |
| `cashu_mint_voucher_rejected_total{reason="funding_required"}` | Issuance rejected with `funding_required` |
| `cashu_mint_voucher_iou_issued_total` | MERCHANT_IOU funding row produced an issuance |
| `cashu_mint_voucher_lazy_funding_total` | Resolver fallback created a `CustomerPaymentFunding` row |
| `cashu_mint_voucher_rate_limit_breach_total{principal=...}` | Per-principal 429 events |

## Cross-repo follow-ups (spec 004)

Spec 004 introduces a salt that should be **shared** with
`imani-gateway-atomic` so cross-system forensic queries can join mint
records to the atomic-side escrow ledger by matching identity hashes.
Two follow-up tracking issues:

- **`imani-gateway-atomic`** — adopt the same `HMAC-SHA-256(salt, npub)`
  scheme on its own `escrow_ledger` identity columns; read the salt
  from the same `CASHU_MINT_VOUCHER_IDENTITY_SALT` env var (or its
  own equivalent that maps to the same secret). See research R2.
- **`imani-apps`** — link `docs/explanations/voucher-data-record.md`
  (rendered on the docs site) from the voucher purchase page header
  per FR-008.

Neither blocks spec 004 from landing — the cashu-mint side is
self-contained.

## Spec 001 / 002 / 003 / 004 reference

- Spec 001 spec: `specs/001-mint-quote-webhook-integrity/`
- Spec 002 spec: `specs/002-melt-burn-ordering/`
- Spec 003 spec: `specs/003-voucher-quote-durability/`
- Spec 004 spec: `specs/004-voucher-data-minimisation/` (operator
  runbook: `specs/004-voucher-data-minimisation/quickstart.md`)
- Customer-facing disclosure: `docs/explanations/voucher-data-record.md`
- Operator reconciliation queries: Javadoc on `MintQuoteJpaRepository`,
  `MeltSagaJpaRepository`, and `VoucherIssuanceJpaRepository`.
- Webhook integrity how-to: `docs/how-to/configure-webhook-integrity.md`.
