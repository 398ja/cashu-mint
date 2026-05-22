# Configure webhook integrity (spec 001)

This guide describes the configuration required so that the mint accepts
payment webhooks safely and binds the `PENDING → PAID` transition to a
durable, append-only `webhook_event` row keyed by `(provider, provider_event_id)`.
The behaviour is mandated by spec 001 (`specs/001-mint-quote-webhook-integrity`).

## Mandatory shared secret in non-local profiles

Spec 001 FR-007 / SC-005 require that the mint **fail to start** in any
non-`local` Spring profile when the webhook HMAC shared secret is missing.
The check is implemented by `WebhookSecretStartupValidator` (a
`@Profile("!local") @Component`).

Set the secret via the environment variable:

```bash
export MINT_WEBHOOK_SECRET="$(openssl rand -hex 32)"
```

…or via the Spring property in `application.properties`:

```properties
cashu.mint.webhook.shared-secret=replace-with-32+ bytes of entropy
```

The legacy `webhook.secret` property is retained for backwards
compatibility but the canonical key going forward is
`cashu.mint.webhook.shared-secret`. Both can be wired to the same
environment variable (the default `application.properties` in
`cashu-mint-rest` already does this).

If you run the service without the `local` profile and leave the secret
unset, the Spring context will refuse to start with:

```
java.lang.IllegalStateException: cashu.mint.webhook.shared-secret is
required in non-local profiles (spec 001 FR-007 / SC-005). Set
MINT_WEBHOOK_SECRET or cashu.mint.webhook.shared-secret in the
application properties.
```

## Provider identifier

The durable webhook idempotency key is `(provider, provider_event_id)`.
The `provider` half is configured via:

```properties
cashu.mint.webhook.provider=phoenixd
```

The default (`phoenixd`) matches the existing Lightning deployment.
Override per environment when a different gateway sources the webhooks
(for example, `cashu.mint.webhook.provider=strike`).

## Enabling the durable persistence path

The new `cashu-mint-jpa` module ships with the entities and Spring Data
repositories required for the durable path. Activate it with:

```properties
cashu.mint.jpa.enabled=true
cashu.mint.jpa.datasource.url=jdbc:postgresql://localhost:5432/cashu_mint
cashu.mint.jpa.datasource.username=cashu_mint
cashu.mint.jpa.datasource.password=...
```

When the flag is `false` (the default for unit-test contexts), the mint
falls back to a cache-only path that still accepts webhooks but does not
record an audit row. The flag must be `true` in `staging` and `prod` for
SC-001/SC-004 to hold.

## Outcome matrix

Once both flags are on, every webhook delivery results in one
`webhook_event` row with one of the following outcomes (spec 001 data
model § WebhookEvent):

| Outcome             | HTTP | Meaning                                                                            |
|---------------------|------|------------------------------------------------------------------------------------|
| `accepted`          | 200  | First-ever delivery; matched quote; CAS `PENDING → PAID` succeeded.                |
| `duplicate`         | 200  | Same `(provider, provider_event_id)` paired with the same body — benign retry.    |
| `tamper`            | 409  | Same `(provider, provider_event_id)` paired with a **different** body. Alert.     |
| `amount_mismatch`   | 422  | Webhook amount does not match `mint_quote.amount`.                                |
| `unit_mismatch`     | 422  | Webhook unit does not match (reserved — current notifications carry no unit).     |
| `method_mismatch`   | 422  | Payment method differs from the persisted quote.                                  |
| `expired`           | 410  | The quote already transitioned to `EXPIRED` before the delivery arrived.           |
| `noop`              | 200  | The quote is already in a non-`PENDING` state (e.g. already `PAID`/`ISSUED`).     |
| `orphan`            | 202  | No `mint_quote` row exists yet for the referenced quoteId.                        |
| `unsigned_rejected` | 401  | Reserved for the strict-signature path (returned by the controller before this).  |
| `signature_invalid` | 401  | Reserved as above.                                                                |

Each outcome increments
`cashu_mint_webhook_event_total{outcome="<outcome>"}` so operators can
alert on `amount_mismatch`, `tamper`, and `signature_invalid` rates.

## Operator reconciliation

A daily invariant check is documented as a doc comment on
`MintQuoteJpaRepository`:

```sql
SELECT SUM(mq.amount) FROM mint_quote mq WHERE mq.lifecycle_state = 'ISSUED'
 = SELECT SUM(ir.total_amount) FROM issuance_record ir
```

Add this to your Grafana / dashboards alongside `cashu_mint_webhook_event_total`.
