# What the mint records about your voucher purchase

**Audience**: customers buying vouchers via an Imani-powered front-end
(typically `imani-apps/voucher/buy.html`).
**Last updated**: 2026-05-23
**Authority**: this document is the source of truth for what the mint
durably stores per voucher purchase. The schema is required to match
(CI test `DisclosureDocSchemaContractTest`).

## TL;DR

When you buy a voucher, the mint stores a record of the financial
transaction (amount, currency, when, payment method) so it can prove the
voucher you hold is backed by a real payment. **Your identity — your
Nostr public key (npub) — is stored as a one-way cryptographic hash, not
as plain text.** After 90 days, even that hash is erased; only the
financial record stays for accounting purposes.

You can also choose to purchase vouchers **anonymously**, in which case
the mint stores no identity at all.

This document explains exactly what's recorded, how it's protected, how
long it's kept, and how to ask the operator what's on file about you.

---

## What we record

For every voucher purchase, the mint stores a row in two tables: one for
the voucher quote (the request) and one for the funding (the payment
that backs it).

### Voucher quote table (`voucher_quote`)

| Column | What it is | Identity-bearing? |
|---|---|---|
| `quote_id` | Random identifier the mint generated for your purchase | No |
| `voucher_type` | E.g. `customer_paid` | No |
| `face_value` | The spendable value of the voucher (e.g. 1000 sats) | No |
| `charged_amount` | What you paid (face value + fees, or fee-only depending on the variant) | No |
| `fee` | The mint's revenue from this purchase | No |
| `original_token_amount` | Sum of blinded message amounts captured at voucher issuance — the original sat-denominated proof sum. Surfaced via `GET /v1/vouchers/{voucherId}/provenance` so a receiving wallet can compute `issuance_ratio = face_value / original_token_amount` and display the correct value of a partial-spend portion rather than the embedded original face value. Null for rows issued before this column existed. | No |
| `unit` | Currency (`sat`, etc.) | No |
| `customer_id` | **Hashed** version of your npub. Null if you purchased anonymously. Nullified after 90 days regardless. | **Yes (hashed)** |
| `merchant_id` | **Hashed** version of the merchant's npub. Nullified after 90 days. | **Yes (hashed)** |
| `funding_id` | Link to the funding-row that backs this voucher | No |
| `lifecycle_state` | Where the purchase is in the issuance pipeline | No |
| `idempotency_key` | The client-supplied retry key | No |
| `request_hash` | Hash of the request body (tamper detection) | No |
| `created_at` / `updated_at` | Timestamps | No |

### Funding tables

The shape depends on the funding source:

**Customer payment** (`customer_payment_funding`): records the Lightning
or other payment that funded the voucher.

| Column | What it is | Identity-bearing? |
|---|---|---|
| `funding_id` | Link back to `voucher_quote.funding_id` | No |
| `amount` / `unit` | Same as on the quote | No |
| `provider` | E.g. `phoenixd` — the payment provider | No |
| `provider_event_id` | The payment provider's event id (e.g. Lightning payment hash). Public network data already. | No |
| `webhook_event_quote_id` | Links to the spec-001 webhook event table | No |
| `customer_id` | **Hashed** mirror of your npub. Null if anonymous. Nullified after 90 days. | **Yes (hashed)** |

**Merchant debit** (`merchant_debit_funding`): records the merchant
ledger entry that funded the voucher (used when a merchant pre-funds
their voucher inventory).

| Column | What it is | Identity-bearing? |
|---|---|---|
| `funding_id` | Link back to `voucher_quote.funding_id` | No |
| `amount` / `unit` | Same as on the quote | No |
| `merchant_id` | **Hashed** version of the merchant's npub. Nullified after 90 days. | **Yes (hashed)** |
| `merchant_debit_id` | External merchant-ledger row id | No |

**Merchant IOU** (`merchant_iou_funding`): rare; used only when the
operator explicitly allows IOUs.

| Column | What it is | Identity-bearing? |
|---|---|---|
| `funding_id` | Link back to `voucher_quote.funding_id` | No |
| `amount` / `unit` | Same as on the quote | No |
| `merchant_id` | **Hashed** version of the merchant's npub. Nullified after 90 days. | **Yes (hashed)** |
| `iou_id` | The IOU contract id | No |
| `iou_terms_hash` | SHA-256 hash of the terms document — proves the terms without storing them | No |
| `iou_terms` | *Deprecated.* The verbatim terms text (spec 003 leftover). Will be dropped after backfill of `iou_terms_hash` is verified across deployments. | No |
| `iou_due_at` | When the IOU is due | No |
| `policy_profile` | The operator policy in effect at issuance | No |

### Voucher issuance ledger (`voucher_issuance`)

A small append-only row that links the quote to the funding to the
issuance receipt. **No identity columns.** Used for the daily
reconciliation that proves the mint hasn't created more vouchers than
it's been paid for.

| Column | What it is | Identity-bearing? |
|---|---|---|
| `voucher_quote_id` | Primary key — same value as `voucher_quote.quote_id` | No |
| `funding_id` | Link to the funding row | No |
| `outputs_hash` | SHA-256 over the blinded outputs that were signed; lets the customer prove "these tokens are mine" without exposing the tokens themselves | No |
| `issued_at` | When the issuance happened | No |

---

## How your identity is protected

**Hashing scheme**: HMAC-SHA-256 with a 256-bit salt that lives only in
the mint's environment (never in the database, never in logs, never in
backups). The hash is a one-way function — given the hash, you cannot
work back to the original npub. Two different npubs produce two
different hashes; the same npub always produces the same hash (so the
mint can do operator-side lookups).

**What an attacker with a database dump sees**: 64 random-looking
hex characters per identity field. No way to enumerate customers from
the dump alone.

**What an operator with the salt can do**: type your npub into the
operator console and find your matching purchases (within the 90-day
retention window). The salt stays inside the mint — operators never
see it directly.

**Anonymous purchases**: if you choose to buy without providing an
npub, the identity fields are stored as null. No hash, no trace, no
"hash of empty string" placeholder that could be enumerated.

## How long we keep your identity

**Identity columns** (`customer_id`, `merchant_id`): **90 days** after
the voucher reaches a terminal state (issued, expired, or failed).
After 90 days, the columns are set to null on both the live row and
every historical revision in the audit shadow. This is configurable
per deployment via `cashu.mint.voucher.identity-retention`; the
default is 90 days.

**Financial columns** (amounts, fees, lifecycle state, funding link):
**retained indefinitely** for accounting / token integrity reconciliation.
These don't identify you, but they prove the mint's books balance.

**The audit log of purge runs** (`voucher_quote_purge_log`): records
when each purge ran and how many rows it affected. Operators can
distinguish "this row was always anonymous" from "this row's identity
was purged on date X."

## Who can read what

| Surface | Identity visibility |
|---|---|
| Operator dashboards (Grafana) | **No raw identity**. Aggregate panels see only financial columns. Per-record panels (e.g. IOU tracker) show a truncated hash (`4f2a1c…`) or `{purged}` after retention. Grafana's database role lacks `SELECT` permission on identity columns — the database refuses the query, not just the dashboard layer. |
| Operator forensic CLI | **Hashed lookup only**. The operator submits your raw npub via an admin REST endpoint; the mint hashes it internally and returns matching rows. The operator never sees the salt. |
| Database backups | **Hashed only**. A backup taken today contains 64-char hex digests, not raw npubs. A backup taken 90+ days ago has nulled identity columns. |
| Mint logs | **No identity**. Identity values are never logged — only quote ids, amounts, and timestamps. |
| Third parties | **Never**. The mint does not share data with third parties as part of normal operation. |

## How to request what's on file about you

Two paths:

1. **Through your wallet / front-end**: contact the operator of the
   service you used to buy the voucher (e.g. the `imani-apps` voucher
   front-end). They can run the operator forensic lookup on your behalf.

2. **Directly**: if you operate the mint yourself, use the admin
   endpoint:

   ```bash
   curl -u "$MINT_ADMIN_USER:$MINT_ADMIN_PASSWORD" \
        -H 'Content-Type: application/json' \
        -d '{"customerNpub": "npub1..."}' \
        https://mint.example/admin/voucher/forensic/customer-purchases
   ```

   The response is a JSON list of your voucher quote ids, amounts, and
   states within the 90-day retention window. Purchases older than 90
   days will not appear (the identity link is gone).

## What this document does NOT cover

- **The voucher proofs themselves** are bearer tokens — only you (or
  whoever holds them) can spend them. The mint cannot link an issued
  proof to a future redemption.
- **Cross-system data**: the merchant or wallet you used may keep its
  own records subject to its own policies. This document is about
  the mint's records only.
- **Anonymous network identification**: this document doesn't address
  IP addresses, TLS metadata, or other network-layer traces. The mint
  doesn't store these; check with the front-end provider for theirs.

## Source code references

For anyone verifying these claims against the implementation:

- Hashing function: `cashu-mint-jpa/src/main/java/.../jpa/crypto/HmacSha256IdentityHasher.java`
- Schema: `cashu-mint-jpa/src/main/resources/db/migration/spec001/V20260524_*.sql` (spec 003) + `V20260601_*.sql` (spec 004)
- Retention purge job: `cashu-mint-jpa/src/main/java/.../jpa/service/VoucherIdentityRetentionPurgeService.java`
- Forensic admin endpoint: `cashu-mint-rest/src/main/java/.../rest/admin/VoucherForensicController.java`

## Changes to this document

This document is checked into source control alongside the schema. Any
change to what's stored requires a matching change here in the same
pull request; CI (`DisclosureDocSchemaContractTest`) fails the build if
they drift apart.
