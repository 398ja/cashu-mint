# Quickstart: Voucher Data Minimisation (Operator Runbook)

**Feature**: 004-voucher-data-minimisation
**Audience**: mint operators deploying spec 004 for the first time, or operating it day-to-day.

This is a procedural guide — not a design doc. For *why* each step exists, see [spec.md](./spec.md) + [research.md](./research.md).

---

## 1. Generate the mint identity salt (one-time, per deployment)

The salt is the privacy anchor. Lose it and you lose the ability to do salt-aware forensic lookups (FR-009); leak it and a DB dump becomes re-identifiable. Treat it like a cryptographic key.

```bash
# Generate 32 bytes of cryptographically random hex (64 hex chars)
openssl rand -hex 32
# Example output: 9f8a2c1b7e4d6a3f0c5b9d8e7f6a4c2b1d8e9f7a3c5b2d4e6f1a8c0b9d7e5f3a
```

**Store this value in your secret manager** (Vault / AWS Secrets Manager / sealed Kubernetes secret). Reference it from the mint's environment as:

```bash
CASHU_MINT_VOUCHER_IDENTITY_SALT=<the-64-char-hex>
```

**Operational discipline:**
- Same salt across every replica of the same mint deployment (single salt per logical mint).
- **Different** salt across dev / staging / prod — never copy prod salt into lower environments.
- **Same** salt across cashu-mint and `imani-gateway-atomic` if you want cross-system forensic correlation (research R2).
- Salt rotation is forbidden in v1 (FR-007); see § 5 below for the offline rotation runbook if you ever need it.

---

## 2. Generate the Grafana read-only DB password

```bash
openssl rand -base64 32
```

Set as the Flyway placeholder:

```bash
CASHU_MINT_GRAFANA_RO_PASSWORD=<the-base64-string>
```

Flyway migration `V20260601_003__grafana_ro_role.sql` uses this to create the `cashu_mint_grafana_ro` role on first boot.

Also wire it into Grafana's data source provisioning (`cashu-mint-observability/docker/grafana/provisioning/datasources/voucher-postgresql.yaml`) via the same env var.

---

## 3. First boot — backfill existing spec-003 rows

If you're deploying spec 004 onto a mint that already has spec 003 data (raw npubs in `voucher_quote.customer_id` etc.), the backfill runs automatically at startup via Spring `@PostConstruct`.

**What to expect:**

```
[INFO ] voucher_identity_backfill start
[INFO ] voucher_identity_backfill batch table=voucher_quote rows=1000 last_pk=q-abc...
[INFO ] voucher_identity_backfill batch table=voucher_quote rows=1000 last_pk=q-def...
...
[INFO ] voucher_identity_backfill complete table=voucher_quote rows=12453 duration_ms=2340
[INFO ] voucher_identity_backfill batch table=customer_payment_funding ...
...
[INFO ] voucher_identity_backfill complete all_tables=8 total_rows=42188 duration_ms=8512
```

**Verify completion:**

```sql
SELECT table_name, rows_hashed, completed_at
FROM voucher_identity_backfill_log
ORDER BY completed_at;
```

Every row should have `completed_at IS NOT NULL` before the voucher endpoints serve traffic. The mint's readiness probe (`/actuator/health/readiness`) returns `DOWN` until backfill completes.

**If the backfill crashes mid-batch:** restart the mint. Idempotency is guaranteed — re-hashing a value yields the same result, and the `voucher_identity_backfill_log.last_hashed_pk` tracker means the second run skips already-hashed ranges.

---

## 4. Verify SC-001 reconciliation post-deploy

Run the daily token-integrity invariant query:

```sql
SELECT COUNT(*) AS orphan_issued_vouchers
FROM voucher_quote
WHERE lifecycle_state = 'ISSUED' AND funding_id IS NULL;
-- Expected: 0
```

If non-zero, the spec-003 funding gate has regressed — **page on-call**. Spec 004 explicitly preserves this invariant (SC-005); a non-zero count means a deploy bug, not a spec-004 expected behaviour.

The Grafana `voucher-token-integrity` dashboard surfaces this as a single-stat panel (FR-015) with an automatic PagerDuty alert wired via Alertmanager.

---

## 5. Operator-facing customer lookup (FR-009 forensic CLI)

When you need to find "did customer X make any voucher purchases in the retention window?", use the admin endpoint:

```bash
curl -u "$MINT_ADMIN_USER:$MINT_ADMIN_PASSWORD" \
     -H 'Content-Type: application/json' \
     -d '{"customerNpub": "npub1abc..."}' \
     https://mint.example/admin/voucher/forensic/customer-purchases
```

The mint hashes the npub internally with the configured salt and returns matching `voucher_quote` rows (within retention; past-retention rows have null identity and won't match).

You **never see the salt** through this endpoint — that's the point. The salt stays in the mint's environment; the operator submits the raw npub.

---

## 6. Retention purge (automated; weekly maintenance check)

The retention purge runs daily via `@Scheduled` (default cron: `0 0 3 * * *` — 3 AM mint-local). After each run, check the audit log:

```sql
SELECT purged_at, retention_cutoff, rows_purged, aud_rows_purged, duration_ms
FROM voucher_quote_purge_log
ORDER BY purged_at DESC LIMIT 7;
```

A healthy week shows steady non-zero `rows_purged` matching your purchase volume from 90 days ago. A sudden zero might indicate the purge job is broken — investigate.

**Verify the purge worked:**

```sql
SELECT COUNT(*)
FROM voucher_quote
WHERE lifecycle_state IN ('ISSUED', 'EXPIRED', 'FAILED')
  AND updated_at < now() - interval '90 days'
  AND (customer_id IS NOT NULL OR merchant_id IS NOT NULL);
-- Expected: 0
```

---

## 7. Salt rotation runbook (offline procedure — emergency use only)

FR-007 forbids salt rotation in v1 for the regular path. v1 does **not ship a salt-rotation tool**; the previous "one-shot rotation tool" example in this section described software that does not exist (called out in the PR #324 review). The reason: rotation requires re-hashing every identity column under the new salt, but the mint never stored the raw npub — only the HMAC. Without the source-of-truth raw value, the mint mathematically cannot transform `HMAC(OLD_SALT, raw)` into `HMAC(NEW_SALT, raw)`.

### What rotation actually means in v1

> **Salt rotation in v1 is a forward-only operation. You accept loss of forensic-lookup capability for every voucher row issued under the old salt.**

If a salt leaks and you MUST rotate:

1. **Decide on rotation scope.** Pre-rotation `voucher_quote`, `customer_payment_funding`, `merchant_debit_funding`, and `merchant_iou_funding` rows will become **forensically opaque** — their stored `customer_id` / `merchant_id` HMACs were computed under the old salt and the FR-009 lookup hashes the operator's raw npub under the new salt. The two will never match. This is the explicit v1 tradeoff; document it in the incident report.
2. **Schedule a brief maintenance window.** A few seconds of voucher-endpoint downtime during the redeploy. No backfill runs — the rotation is the redeploy, not a data migration.
3. **Generate the new salt** as in § 1, store it under a new version in the secret manager, leave the old salt entry in place for ≥ 7 days for audit / rollback.
4. **Stop the mint** — drain in-flight requests; `kubectl scale deploy/cashu-mint --replicas=0` or equivalent.
5. **Redeploy with the new salt env var.** Update `CASHU_MINT_VOUCHER_IDENTITY_SALT` to point at the new secret version; deploy; verify `/actuator/health` is `UP`.
6. **Test the FR-009 endpoint with a post-rotation npub** to confirm new lookups work. (Pre-rotation lookups return zero matches — that is expected and is the visible signal that rotation completed.)
7. **Burn the old salt** from the secret manager after the audit window expires.

### What rotation does NOT do

- It does NOT re-hash existing rows. There is no `rotate-identity-salt` command, no `--rehash-existing` flag, no batch job.
- It does NOT preserve cross-rotation forensic lookups. A row issued at 09:00 under salt A and a row issued at 10:00 under salt B for the same customer have unrelated `customer_id` HMACs after rotation; a single operator lookup at 11:00 returns only the 10:00 row.
- It is NOT routine. v2 may add a salt-versioned column (`identity_salt_version`) so rotation is online and lookups can hash under multiple historical salts — but until then, treat rotation as a one-way break in forensic continuity.

### Why this is acceptable

The salt's job is to make a DB dump non-re-identifiable by an attacker who lacks the salt — the customer-disclosure document (§ 8) is explicit that forensic lookup is a privileged operator path, not a customer-facing guarantee. Losing lookup capability for the pre-rotation window degrades operations (operators answer "we cannot tell" for pre-rotation queries) but does not break any FR — FR-009 binds the post-rotation set, not the historical set.

---

## 8. Customer disclosure document

The FR-001 disclosure lives at `docs/explanations/voucher-data-record.md` (rendered on the docs site at https://docs.mint.example/explanations/voucher-data-record). The `imani-apps` voucher purchase page links to it from the header.

If you change anything about what's stored — adding a column, changing the retention default, adding a funding variant — **update the disclosure document in the same PR**. The CI check (SC-003) re-renders the document from the schema and diffs; a drift fails the build.

---

## 9. Common operator queries

### "How much voucher liability do we have outstanding right now?"

```sql
SELECT f.funding_source, SUM(f.amount) AS liability
FROM voucher_funding f
JOIN voucher_quote q ON q.funding_id = f.funding_id
                    AND q.lifecycle_state = 'ISSUED'
GROUP BY f.funding_source;
```

(Or: open the `voucher-liability-overview` Grafana dashboard.)

### "Which IOUs are overdue?"

```sql
SELECT i.iou_id, i.iou_due_at,
       now() - i.iou_due_at AS overdue_by, f.amount
FROM merchant_iou_funding i
JOIN merchant_iou_funding f ON f.funding_id = i.funding_id
WHERE i.iou_due_at < now()
ORDER BY i.iou_due_at;
```

(Or: open the `voucher-iou-liability` Grafana dashboard.)

### "Was customer X ever issued a voucher in the last 90 days?"

Use the FR-009 admin endpoint (§ 5 above). Direct SQL won't work — `customer_id` is HMAC-hashed and you'd need to compute the same hash with the salt, which only the mint has.

### "When was this voucher's identity purged?"

```sql
SELECT q.quote_id, q.updated_at, p.purged_at
FROM voucher_quote q
LEFT JOIN voucher_quote_purge_log p
       ON q.updated_at < p.retention_cutoff
WHERE q.quote_id = 'q-abc...'
  AND q.customer_id IS NULL
ORDER BY p.purged_at LIMIT 1;
```

If `p.purged_at` is non-null, the row was purged (FR-003). If null, the row was created anonymously (FR-019) — never had identity.

---

## 10. Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| Mint won't start: `cashu.mint.voucher.identity-salt is required` | Env var unset | Set `CASHU_MINT_VOUCHER_IDENTITY_SALT` from secret manager |
| Mint won't start: `... must be at least 256 bits of entropy` | Salt < 64 hex chars / < 32 bytes | Regenerate with `openssl rand -hex 32` |
| Backfill stuck on one table | Check `voucher_identity_backfill_log.last_hashed_pk` — find the PK; check the raw row for malformed data (e.g. an old npub that's longer than VARCHAR(255)) | Manually `UPDATE` the problem row OR widen the column |
| Grafana panel shows `permission denied for column customer_id` | A new panel referenced an identity column | Either rewrite the panel without identity (preferred) OR move it to the forensic CLI surface |
| Daily purge `rows_purged = 0` for a week with active mint | Purge job not running OR retention misconfigured | Check `/actuator/scheduledtasks`; check `cashu.mint.voucher.identity-retention` value |
| Forensic CLI returns empty for a known customer | Query is outside retention window | Confirm `voucher_quote_purge_log` shows a purge covering the customer's purchase date |
