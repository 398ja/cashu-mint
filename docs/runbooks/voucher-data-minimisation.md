# Voucher Data Minimisation (spec 004) — operator runbook

**Audience**: mint operators who need to deploy, monitor, or recover
from issues with the spec-004 voucher data-custody layer.

**Authoritative runbook**: the bulk of operational procedure lives in
[`specs/004-voucher-data-minimisation/quickstart.md`](../../specs/004-voucher-data-minimisation/quickstart.md).
This page is a thin pointer for operators who land in `docs/runbooks/`
first (the same place [`virtual-thread-issues.md`](virtual-thread-issues.md)
lives).

## Quick links

| What | Where |
|---|---|
| **First-deploy procedure** (salt generation, Grafana DB password, first backfill, SC-001 verification) | [`quickstart.md` § 1-4](../../specs/004-voucher-data-minimisation/quickstart.md) |
| **Forensic lookup CLI** (find a customer's purchases by raw npub) | [`quickstart.md` § 5](../../specs/004-voucher-data-minimisation/quickstart.md) + [`cashu-mint-rest/README.md`](../../cashu-mint-rest/README.md) § `POST /admin/voucher/forensic/...` |
| **Daily retention purge** monitoring (`voucher_quote_purge_log`) | [`quickstart.md` § 6](../../specs/004-voucher-data-minimisation/quickstart.md) |
| **Salt rotation** emergency procedure | [`quickstart.md` § 7](../../specs/004-voucher-data-minimisation/quickstart.md) |
| **Customer disclosure document** (what's stored about a customer) | [`docs/explanations/voucher-data-record.md`](../explanations/voucher-data-record.md) |
| **Troubleshooting** common errors | [`quickstart.md` § 10](../../specs/004-voucher-data-minimisation/quickstart.md) |
| **Architecture overview** | [`CLAUDE.md` § "Spec 004 — Voucher Data Minimisation"](../../CLAUDE.md) |
| **Spec** (FRs, SCs, design rationale) | [`specs/004-voucher-data-minimisation/spec.md`](../../specs/004-voucher-data-minimisation/spec.md) |

## Common operator queries (one-line cheat sheet)

```bash
# 1. Generate a salt (one-time, per deployment)
openssl rand -hex 32

# 2. Look up a customer's purchases (in-window only)
curl -u "$MINT_ADMIN_USER:$MINT_ADMIN_PASSWORD" \
     -H 'Content-Type: application/json' \
     -d '{"customerNpub":"npub1..."}' \
     https://mint.example/admin/voucher/forensic/customer-purchases

# 3. Verify the daily purge ran
psql -c "SELECT purged_at, rows_purged, aud_rows_purged FROM voucher_quote_purge_log ORDER BY purged_at DESC LIMIT 7"

# 4. Verify SC-001 (zero orphan issuances)
psql -c "SELECT COUNT(*) FROM voucher_quote WHERE lifecycle_state = 'ISSUED' AND funding_id IS NULL"

# 5. Verify zero raw npubs in production (post-backfill SC-001 lint)
psql -c "SELECT COUNT(*) FROM voucher_quote WHERE customer_id IS NOT NULL AND customer_id !~ '^[0-9a-f]{64}\$'"
```

## When to look elsewhere

- **Customer asks "what do you know about me?"** — point them to
  [`docs/explanations/voucher-data-record.md`](../explanations/voucher-data-record.md).
  The customer-facing language is there; this runbook is operator-facing.
- **Code-level details** — see the spec module Javadoc:
  - `cashu-mint-protocol/.../ports/IdentityHasher.java`
  - `cashu-mint-jpa/.../crypto/HmacSha256IdentityHasher.java`
  - `cashu-mint-jpa/.../service/VoucherIdentityBackfillService.java`
  - `cashu-mint-jpa/.../service/VoucherIdentityRetentionPurgeService.java`
  - `cashu-mint-rest/.../controller/admin/VoucherForensicController.java`
