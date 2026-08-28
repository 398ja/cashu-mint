# Voucher Data Minimisation — operator runbook

**Audience**: mint operators who need to deploy, monitor, or recover from issues
with the voucher data-custody layer.

Customer identities (npubs) are never stored raw. They are HMAC-SHA256 hashed
under a per-deployment salt, and purged entirely once past the retention window.
That makes a database dump non-re-identifiable by anyone who lacks the salt, and
it is why several ordinary questions ("which vouchers did this customer buy?")
have to go through a privileged endpoint rather than a SQL query.

For what is stored about a customer and why, in customer-facing language, see
[Voucher data record](../explanations/voucher-data-record.md).

## 1. Generate the mint identity salt (one-time, per deployment)

The salt is the privacy anchor. Lose it and salt-aware forensic lookups become
impossible; leak it and a database dump becomes re-identifiable. Treat it as a
cryptographic key.

```bash
openssl rand -hex 32
```

Store the value in your secret manager and reference it from the mint's
environment:

```bash
CASHU_MINT_VOUCHER_IDENTITY_SALT=<the-64-char-hex>
```

The mint refuses to start without it, and refuses a salt below 256 bits.

Operational discipline:

- The same salt on every replica of one logical mint.
- A **different** salt per environment. Never copy a production salt downstream.
- The same salt across the mint and `imani-gateway-atomic` if you want
  cross-system forensic correlation.
- Rotation is forward-only and lossy. See § 6 before you consider it.

## 2. Generate the Grafana read-only database password

```bash
openssl rand -base64 32
```

```bash
CASHU_MINT_GRAFANA_RO_PASSWORD=<the-base64-string>
```

Flyway migration `V20260601_003__grafana_ro_role.sql` uses this to create the
`cashu_mint_grafana_ro` role on first boot. Wire the same variable into Grafana's
data source provisioning at
`cashu-mint-observability/docker/grafana/provisioning/datasources/voucher-postgresql.yaml`.

## 3. First boot: backfill existing rows

Deploying onto a mint that already holds raw npubs runs a backfill automatically
at startup. Expect:

```
[INFO ] voucher_identity_backfill start
[INFO ] voucher_identity_backfill batch table=voucher_quote rows=1000 last_pk=q-abc...
[INFO ] voucher_identity_backfill complete table=voucher_quote rows=12453 duration_ms=2340
[INFO ] voucher_identity_backfill complete all_tables=8 total_rows=42188 duration_ms=8512
```

Verify every table finished before the voucher endpoints serve traffic:

```sql
SELECT table_name, rows_hashed, completed_at
FROM voucher_identity_backfill_log
ORDER BY completed_at;
```

The readiness probe (`/actuator/health/readiness`) stays `DOWN` until the
backfill completes, so a rolling deploy will not send traffic to a half-hashed
mint.

If the backfill crashes mid-batch, restart the mint. It is idempotent: re-hashing
a value yields the same result, and `voucher_identity_backfill_log.last_hashed_pk`
means the second run skips ranges already done.

Batch size is `CASHU_MINT_VOUCHER_IDENTITY_BACKFILL_BATCH_SIZE` (default 1000).

## 4. Verify token integrity after deploying

```sql
SELECT COUNT(*) AS orphan_issued_vouchers
FROM voucher_quote
WHERE lifecycle_state = 'ISSUED' AND funding_id IS NULL;
-- Expected: 0
```

A non-zero count means the funding gate has regressed — page on-call. This is a
deploy bug, never expected behaviour. The Grafana `voucher-token-integrity`
dashboard surfaces it as a single-stat panel with an Alertmanager alert.

## 5. Look up a customer's purchases

Direct SQL will not answer this: `customer_id` holds an HMAC, and computing the
same hash requires the salt, which only the mint has. Use the admin endpoint,
which hashes the npub internally:

```bash
curl -u "$MINT_ADMIN_USER:$MINT_ADMIN_PASSWORD" \
     -H 'Content-Type: application/json' \
     -d '{"customerNpub": "npub1abc..."}' \
     https://mint.example/admin/voucher/forensic/customer-purchases
```

The merchant equivalent takes `merchantNpub`:

```bash
curl -u "$MINT_ADMIN_USER:$MINT_ADMIN_PASSWORD" \
     -H 'Content-Type: application/json' \
     -d '{"merchantNpub": "npub1xyz..."}' \
     https://mint.example/admin/voucher/forensic/merchant-purchases
```

Both return the hash, a match count, and the matching rows. The salt is never
exposed through the endpoint — that is the point. Rows past retention carry no
identity and therefore never match.

## 6. Salt rotation (emergency only, and lossy)

There is no salt-rotation tool, and rotation cannot re-hash existing rows. The
mint stored only the HMAC, never the raw npub, so it cannot transform
`HMAC(OLD_SALT, raw)` into `HMAC(NEW_SALT, raw)` — the input no longer exists.

> Rotation is forward-only. Every row issued under the old salt becomes
> forensically opaque, permanently.

If a salt leaks and you must rotate:

1. **Accept the loss.** Pre-rotation `voucher_quote`, `customer_payment_funding`,
   `merchant_debit_funding` and `merchant_iou_funding` rows stop matching any
   lookup. Record this in the incident report.
2. **Schedule a short maintenance window.** Seconds of voucher-endpoint downtime
   during the redeploy. There is no data migration.
3. **Generate a new salt** as in § 1, store it as a new secret version, and keep
   the old entry for at least 7 days for audit and rollback.
4. **Stop the mint**, draining in-flight requests.
5. **Redeploy** with `CASHU_MINT_VOUCHER_IDENTITY_SALT` pointing at the new
   version. Confirm `/actuator/health` is `UP`.
6. **Test a lookup with a post-rotation npub.** Pre-rotation lookups returning
   nothing is the expected signal that rotation took effect.
7. **Burn the old salt** once the audit window expires.

This degrades operations rather than breaking a guarantee: forensic lookup is a
privileged operator path, not a customer-facing promise. A future salt-versioned
column could make rotation online, but until then treat it as a one-way break in
forensic continuity.

## 7. Retention purge (automated, worth a weekly glance)

The purge runs daily via `@Scheduled`, at 03:00 mint-local by default
(`CASHU_MINT_VOUCHER_IDENTITY_PURGE_CRON`). Retention defaults to 90 days
(`CASHU_MINT_VOUCHER_IDENTITY_RETENTION`, `PT2160H`).

```sql
SELECT purged_at, retention_cutoff, rows_purged, aud_rows_purged, duration_ms
FROM voucher_quote_purge_log
ORDER BY purged_at DESC LIMIT 7;
```

A healthy week shows steady non-zero `rows_purged` tracking your volume from 90
days earlier. A sudden run of zeroes suggests the job is not running: check
`/actuator/scheduledtasks` and the retention setting.

Confirm the purge actually removed identities:

```sql
SELECT COUNT(*)
FROM voucher_quote
WHERE lifecycle_state IN ('ISSUED', 'EXPIRED', 'FAILED')
  AND updated_at < now() - interval '90 days'
  AND (customer_id IS NOT NULL OR merchant_id IS NOT NULL);
-- Expected: 0
```

## 8. Common operator queries

**Outstanding voucher liability** (or open the `voucher-liability-overview`
dashboard):

```sql
SELECT f.funding_source, SUM(f.amount) AS liability
FROM voucher_funding f
JOIN voucher_quote q ON q.funding_id = f.funding_id
                    AND q.lifecycle_state = 'ISSUED'
GROUP BY f.funding_source;
```

**Overdue IOUs** (or the `voucher-iou-liability` dashboard):

```sql
SELECT i.iou_id, i.iou_due_at,
       now() - i.iou_due_at AS overdue_by, f.amount
FROM merchant_iou_funding i
JOIN merchant_iou_funding f ON f.funding_id = i.funding_id
WHERE i.iou_due_at < now()
ORDER BY i.iou_due_at;
```

**No raw npubs left after backfill:**

```sql
SELECT COUNT(*) FROM voucher_quote
WHERE customer_id IS NOT NULL AND customer_id !~ '^[0-9a-f]{64}$';
-- Expected: 0
```

**When was this voucher's identity purged?**

```sql
SELECT q.quote_id, q.updated_at, p.purged_at
FROM voucher_quote q
LEFT JOIN voucher_quote_purge_log p
       ON q.updated_at < p.retention_cutoff
WHERE q.quote_id = 'q-abc...'
  AND q.customer_id IS NULL
ORDER BY p.purged_at LIMIT 1;
```

A non-null `purged_at` means the row was purged. Null means it was created
anonymously and never carried an identity.

## 9. Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| Won't start: `cashu.mint.voucher.identity-salt is required` | Env var unset | Set `CASHU_MINT_VOUCHER_IDENTITY_SALT` from the secret manager |
| Won't start: `... must be at least 256 bits of entropy` | Salt shorter than 64 hex chars | Regenerate with `openssl rand -hex 32` |
| Backfill stuck on one table | Malformed row | Find it via `voucher_identity_backfill_log.last_hashed_pk`; fix the row or widen the column |
| Grafana: `permission denied for column customer_id` | A panel referenced an identity column | Rewrite the panel without identity, or move it to the forensic endpoint |
| Purge reports `rows_purged = 0` for a week | Job not running, or retention misconfigured | Check `/actuator/scheduledtasks` and `cashu.mint.voucher.identity-retention` |
| Forensic lookup empty for a known customer | Query falls outside the retention window | Check `voucher_quote_purge_log` for a purge covering that date |

## Changing what is stored

If you add a column, change the retention default, or add a funding variant,
update [`docs/explanations/voucher-data-record.md`](../explanations/voucher-data-record.md)
in the same change. `DisclosureDocSchemaContractTest` re-renders that document
from the schema and fails the build on drift.

## Code-level detail

- `cashu-mint-protocol/.../ports/IdentityHasher.java`
- `cashu-mint-jpa/.../crypto/HmacSha256IdentityHasher.java`
- `cashu-mint-jpa/.../service/VoucherIdentityBackfillService.java`
- `cashu-mint-jpa/.../service/VoucherIdentityRetentionPurgeService.java`
- `cashu-mint-rest/.../controller/admin/VoucherForensicController.java`
