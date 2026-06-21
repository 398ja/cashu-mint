# Enable the Trace Producer (spec 036)

Make the mint emit signed `kind-9079` trace events to the `cashu-ledger` forensic ledger. The mint
emits only what it legitimately knows — Lightning quote requests and the two failures it owns. It
never emits output (minted-token) proof identities; the proof-level token-flow chain is produced
wallet-side (separate repository).

**Disabled by default.** When `cashu.trace.publisher.enabled=false` (the default) no trace beans are
created and mint behaviour is identical to having the feature absent.

## What gets emitted

| Event | When |
|-------|------|
| `MINT_QUOTE_REQUESTED` | a mint quote is created |
| `MELT_QUOTE_REQUESTED` | a melt quote is created (carries the fee reserve) |
| `MINT_FAILED` | an unpaid/invalid issuance is rejected (no proofs) |
| `MELT_FAILED` | a melt payment fails and the input proofs are released (inputs by public `Y` only — never the secret) |

## Configuration

| Property | Env | Notes |
|----------|-----|-------|
| `cashu.trace.publisher.enabled` | `CASHU_TRACE_PUBLISHER_ENABLED` | Master switch. Default `false`. |
| `cashu.trace.publisher.private-key-hex` | `CASHU_TRACE_PUBLISHER_PRIVATE_KEY_HEX` | Producer signing key. **Secret — env only.** Boot fails closed if enabled & blank. |
| `cashu.trace.publisher.relays` | `CASHU_TRACE_PUBLISHER_RELAYS` | Comma-separated ledger relays. Discoverable via the ledger `GET /api/v1/trace/relays`. |
| `cashu.trace.publisher.outbox-jdbc-url` | `CASHU_TRACE_PUBLISHER_OUTBOX_JDBC_URL` | Durable outbox + operation-id registry. **Use a file path** for restart survival; the in-memory default is non-durable. |
| `cashu.mint.url` | `CASHU_MINT_URL` | Mint identity events are attributed to. Required when enabled. |

Example (enabled):

```properties
cashu.trace.publisher.enabled=true
cashu.trace.publisher.private-key-hex=${CASHU_TRACE_PUBLISHER_PRIVATE_KEY_HEX}
cashu.trace.publisher.relays=wss://relay-a.internal,wss://relay-b.internal
cashu.trace.publisher.outbox-jdbc-url=jdbc:sqlite:/var/lib/cashu-mint/trace-outbox.db
cashu.mint.url=https://mint.example.com
```

## Fail-closed boot

When `enabled=true`, startup **aborts** unless the signing key, at least one relay, and `cashu.mint.url`
are all set. A producer that is on but cannot sign or address a relay would silently drop events, which
is worse than being off.

## Operator wiring on the ledger

Authorise this mint's producer pubkey on the **ledger** under `trace.ingest.producers`
(`mint-url → pubkeys`, see the ledger `docs/how-to/operate-trace-ledger.md`). Until then the ledger
drops the mint's events at ingest — they are still emitted and signed, just not accepted.

## Safety properties

- **Fire-and-forget**: a tracing fault (relay down, signing/serialisation error) is logged and
  swallowed — it can never fail or block a mint/melt operation.
- **Committed-only**: events fire after the durable quote/issuance/refund, on the success or
  known-failure branch.
- **Idempotent**: a retry/restart re-emitting the same operation resolves to one ledger record
  (durable operation-id registry, keyed by quote id).
- **No secrets**: the producer signing key lives in the environment only; emitted events carry no
  customer identity and no plaintext proof secrets.

## Rollback

Set `cashu.trace.publisher.enabled=false` and restart. No schema to revert — the SQLite outbox is a
self-contained file that can be deleted once drained.
