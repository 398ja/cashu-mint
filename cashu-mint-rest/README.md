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
```

## Admin endpoints (internal-only)

The admin endpoints are loaded only when the spec-002 melt saga
infrastructure is wired in (i.e. `cashu.mint.jpa.enabled=true` and the
`MeltSagaRepository` bean is present). They are intended for operator
tooling and reconciliation workflows.

> **Security note:** these endpoints are NOT yet protected by Spring
> Security service-account authentication (tracked as a follow-up).
> Until that lands, deployments MUST:
>
> - bind the mint service to an internal-only network interface, or
> - front it with a reverse proxy that filters `/admin/**` paths by
>   IP allow-list / mTLS / OIDC, or
> - apply equivalent network-level protection.

### `GET /admin/melt-saga/by-id/{meltSagaId}`

Returns the full `MeltSagaResponse` (current state, all amount fields,
provider metadata, full transition timeline) for a given saga.

```bash
curl http://localhost:7777/admin/melt-saga/by-id/4a3e...
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

## Spec 001 / 002 reference

- Spec 001 spec: `specs/001-mint-quote-webhook-integrity/`
- Spec 002 spec: `specs/002-melt-burn-ordering/`
- Operator reconciliation queries: Javadoc on `MintQuoteJpaRepository`
  and `MeltSagaJpaRepository`.
- Webhook integrity how-to: `docs/how-to/configure-webhook-integrity.md`.
