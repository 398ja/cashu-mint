# REST API Reference
This reference lists the public endpoints exposed by `cashu-mint-rest`. All routes are rooted at `/v1`. Administrative APIs are published from the separate `cashu-mint-admin` project.

## Keys

### `GET /v1/keys/keyset/{keyset_id}`
Return the public keys for a specific keyset id.
- `keyset_id` (path) – keyset identifier.

Example:
```http
GET /v1/keys/keyset/abc123 HTTP/1.1
```
Sample response:
```json
{
  "keysets": [
    {
      "id": "abc123",
      "unit": "sat",
      "keys": { "1": "0275…", "2": "03ab…" }
    }
  ]
}
```

### `GET /v1/keysets`
List active keysets (with their `active` flag). No parameters.

Example:
```http
GET /v1/keysets HTTP/1.1
```
Sample response:
```json
{
  "keysets": [
    { "id": "abc123", "unit": "sat", "active": true }
  ]
}
```

## Swap

### `POST /v1/swap`
Swap tokens within a mint. The mint id is inferred from the input proofs' keyset ids.

Body (abridged):
```json
{
  "inputs": [ { /* proof */ } ],
  "outputs": [ { /* blinded message */ } ]
}
```

Returns a standard NUT-03 swap response or `400` when inputs are missing/empty.

## Mint quotes

### `POST /v1/mint/quote/{method}`
Create a mint quote for a payment method (for example `bolt11`).
- `method` (path) – payment method name (case-insensitive).
- Body: `{ "amount": 1000 }`.

### `GET /v1/mint/quote/{method}/{quote_id}`
Check mint quote status.
- `method` (path) – payment method.
- `quote_id` (path) – quote identifier returned from the POST.

### `POST /v1/mint/quote/voucher/{method}`
Create a voucher mint quote that charges a percentage fee (see `voucher.quote.fee-percent`).

### `GET /v1/mint/quote/voucher/{method}/{quote_id}`
Check voucher mint quote status.

## Mint tokens

### `POST /v1/mint/{method}`
Mint tokens after paying a quote.
- `method` (path) – payment method.
- Body fields:
  - `quote_id` – required.
  - `blinded_messages` – required; each output must include `keyset_id` so the controller can infer the mint id.

Returns `400` when the quote id or outputs are missing; `404` when the mint or quote cannot be resolved.

## Melt quotes

### `POST /v1/melt/quote/{method}`
Request a melt quote for a payment method.
- `method` (path) – payment method.
- Body: `{ "amount": 1000, "unit": "sat" }`.

### `GET /v1/melt/quote/{method}/{quote_id}`
Check melt quote status.

## Melt tokens

### `POST /v1/melt/{method}`
Melt tokens using a paid quote. The mint id is inferred from the input proofs.
- `method` (path) – payment method.
- Body fields:
  - `quote_id` – required.
  - `inputs` – required list of proofs that includes `keyset_id`.

Returns `400` for missing fields; `404` when the mint or quote cannot be resolved.

## Mint info

### `GET /v1/info`
Return NUT-06 mint information plus legacy helper fields (`units`, `mint_methods`, `melt_methods`). No parameters.

## Token state checks

### `POST /v1/checkstate`
Return the token state (UNSPENT/PENDING/SPENT) for each secret, merging results across active and archived mints.
- Body: `{ "hash_to_curve_secrets": [ "02ab…" ] }`.

## Restore signatures

### `POST /v1/restore`
Restore previously generated blind signatures (NUT-09) using the shared `SignatureVaultService`.
- Body: `{ "blinded_messages": [ { "B_": "…" } ] }`.

## Vouchers (voucher profile)

Voucher endpoints are available when the `voucher` Spring profile is active and `voucher.enabled=true`.

### `POST /v1/vouchers`
Issue a voucher (gift card) signed by the mint's issuer key.
- Body: `issuerId`, `unit`, `amount`, optional `expiresInDays`, `memo`.

### `GET /v1/vouchers/{voucherId}/status`
Return the status of a voucher from the Nostr ledger (`ISSUED`, `REDEEMED`, `REVOKED`, `EXPIRED`).

## Errors

- Unpaid Lightning invoices return `402 Payment Required` with error code `mint_invoice_not_paid_error`.
- Validation errors return `400 Bad Request`; not-found resources return `404 Not Found`.
