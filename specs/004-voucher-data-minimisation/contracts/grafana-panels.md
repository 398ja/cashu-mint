# Contract: Grafana Operator Dashboard Panels

**Feature**: 004-voucher-data-minimisation
**Phase**: 1
**Spec refs**: FR-013 through FR-018, SC-007 through SC-010, SC-012
**Module**: `cashu-mint-observability/docker/grafana/dashboards/`

Three new dashboards; one existing dashboard updated. Every SQL panel runs as the `cashu_mint_grafana_ro` role (data-model.md), which lacks `SELECT` on identity columns. Every PromQL panel reads metrics emitted by the existing `cashu-mint-observability` instrumentation.

---

## Dashboard 1: `voucher-liability-overview.json`

**FR-014**. Audience: financial operators. Read-only aggregate view of voucher liability.

| Panel | Type | Query source | Query |
|---|---|---|---|
| **Total outstanding liability** | Single stat | PostgreSQL | `SELECT SUM(face_value) FROM voucher_quote WHERE lifecycle_state = 'ISSUED'` |
| **Liability per funding source** | Stacked bar | PostgreSQL | `SELECT f.funding_source, SUM(f.amount) FROM voucher_funding f JOIN voucher_quote q ON q.funding_id = f.funding_id AND q.lifecycle_state = 'ISSUED' GROUP BY f.funding_source` |
| **30-day liability trend** | Time-series | PostgreSQL | `SELECT date_trunc('day', i.issued_at) AS day, f.funding_source, SUM(f.amount) AS daily_liability FROM voucher_issuance i JOIN voucher_funding f ON f.funding_id = i.funding_id WHERE i.issued_at > now() - interval '30 days' GROUP BY 1, 2 ORDER BY 1` |
| **Per-source 30-day issuance rate** | Time-series | Prometheus | `rate(cashu_mint_voucher_issued_total{path="mint"}[5m]) by (funding_source)` |

**Privacy property**: zero identity columns referenced. Works identically pre- and post-purge. SC-007 covers.

## Dashboard 2: `voucher-token-integrity.json`

**FR-015**. Audience: SRE / on-call. Token-integrity reconciliation made glanceable.

| Panel | Type | Query source | Query | Alert |
|---|---|---|---|---|
| **Orphan issued vouchers** | Single stat | PostgreSQL | `SELECT COUNT(*) FROM voucher_quote WHERE lifecycle_state = 'ISSUED' AND funding_id IS NULL` | **Threshold > 0 sustained > 1min → PagerDuty `voucher_orphan_issuance` route** |
| **Issued-vs-funded reconciliation** | Side-by-side stats | PostgreSQL | `SELECT 'issued' AS side, SUM(q.face_value) FROM voucher_quote q WHERE q.lifecycle_state='ISSUED' UNION ALL SELECT 'funded', SUM(f.amount) FROM voucher_funding f JOIN voucher_quote q ON q.funding_id=f.funding_id AND q.lifecycle_state='ISSUED'` | Delta != 0 → red highlight + Slack `#vouchers-ops` |
| **Stuck quotes by state** | Table | PostgreSQL | `SELECT lifecycle_state, COUNT(*) FROM voucher_quote WHERE lifecycle_state IN ('UNFUNDED','FUNDED','ISSUING') AND updated_at < now() - interval '1 hour' GROUP BY lifecycle_state` | Any row → Slack `#vouchers-ops` |
| **Funding-required rejection rate** | Time-series | Prometheus | `rate(cashu_mint_voucher_funding_required_total[5m])` | Sustained > 0.1/s → Slack |

**Synthetic alert IT**: FR-018 — `IntegrityAlertSyntheticIT` injects an orphan ISSUED voucher_quote row, polls Alertmanager's `/api/v1/alerts` API for 5 minutes, asserts the alert fires through to a captured webhook receiver.

## Dashboard 3: `voucher-iou-liability.json`

**FR-016**. Audience: merchant relations + finance. Privacy-aware per-record view.

| Panel | Type | Query source | Query | Display |
|---|---|---|---|---|
| **Outstanding IOUs** | Table | PostgreSQL | `SELECT i.iou_id, f.merchant_id, i.policy_profile, f.amount, i.iou_due_at, CASE WHEN i.iou_due_at < now() THEN 'overdue' WHEN i.iou_due_at < now() + interval '7 days' THEN 'due_soon' ELSE 'ok' END AS status FROM merchant_iou_funding f JOIN voucher_quote q ON q.funding_id = f.funding_id AND q.lifecycle_state = 'ISSUED' JOIN merchant_iou_funding i ON i.funding_id = f.funding_id ORDER BY i.iou_due_at ASC LIMIT 200` | `merchant_id` rendered as **first 8 chars + `…`** when non-null, `{purged}` sentinel when null AND `created_at` past retention. Grafana field-override formatter. |
| **Policy drift** | Single stat | PostgreSQL | `SELECT COUNT(*) FROM merchant_iou_funding WHERE policy_profile = 'prod' AND policy_profile != current_setting('cashu.mint.voucher.iou-policy', true)` | Threshold > 0 → Slack `#merchant-finance` |
| **Overdue IOU value** | Single stat | PostgreSQL | `SELECT SUM(f.amount) FROM merchant_iou_funding i JOIN merchant_iou_funding f ON i.funding_id = f.funding_id WHERE i.iou_due_at < now()` | Any value > 0 → Slack `#merchant-finance` daily digest |

**CRITICAL**: this dashboard MUST NOT offer any "reveal raw merchant_id" action. Even the `cashu_mint_grafana_ro` role cannot read `merchant_id` raw; the column above is the HMAC hash. Identity recovery is exclusively via the FR-009 `VoucherForensicController` REST endpoint (which has the salt). SC-008 covers the no-reveal contract.

## Dashboard 4: `cashu-mint-business.json` (existing — modified)

**FR-017**. Metric naming reconciliation per research R6.

| Change | From | To |
|---|---|---|
| Metric reference | `cashu_mint_vouchers_face_value_issued_total` | `cashu_mint_voucher_face_value_issued_total` |
| Metric reference | `cashu_mint_vouchers_face_value_redeemed_total` | `cashu_mint_voucher_face_value_redeemed_total` |
| Metric reference | `cashu_mint_vouchers_fees_collected_total` | `cashu_mint_voucher_fees_collected_total` |
| Metric reference | `cashu_mint_vouchers_issued_total` | `cashu_mint_voucher_issued_total` |
| Metric reference | `cashu_mint_vouchers_quotes_active` | `cashu_mint_voucher_quotes_active` |
| Metric reference | `cashu_mint_vouchers_redeemed_total` | `cashu_mint_voucher_redeemed_total` |
| Metric reference | `cashu_mint_vouchers_rejected_total` | `cashu_mint_voucher_rejected_total` |

**Backward compatibility**: `cashu-mint-observability/docker/prometheus/prometheus.yml` gets `metric_relabel_configs` rules that alias old plural names to new singular names for one release cycle. After the release-cycle window, the relabel rules are deleted; SC-010 asserts.

---

## Required dashboard JSON contracts

Every dashboard JSON file MUST:

1. **Provision via existing path** (`cashu-mint-observability/docker/grafana/dashboards/`) — picked up automatically by the Grafana container.
2. **Use the `cashu_mint_grafana_ro` data source for PostgreSQL panels** — Grafana provisioning binds this in `provisioning/datasources/voucher-postgresql.yaml`.
3. **Use the existing Prometheus data source for PromQL panels** — already provisioned.
4. **Carry a stable UID** (`voucher-liability-overview`, `voucher-token-integrity`, `voucher-iou-liability`) so deep-links from runbook docs don't break across deploys.
5. **NOT reference any column NOT granted to `cashu_mint_grafana_ro`** — the CI check (SC-007 + SC-012) inspects the dashboard JSON and asserts.

## Field-override formatters

For the IOU table (Dashboard 3), the truncation + `{purged}` rendering uses Grafana field overrides:

```json
{
  "matcher": {"id": "byName", "options": "merchant_id"},
  "properties": [
    {"id": "mappings", "value": [
      {"type": "value", "options": {"": {"text": "{purged}"}}}
    ]},
    {"id": "custom.transform", "value": "substring(0,8) + '…'"}
  ]
}
```

(Exact JSON form per Grafana 10.x schema; the principle is the same — display formatter, not query transformation, so no panel can be modified to reveal raw values.)

## SC mapping

| SC | Covered by |
|---|---|
| SC-007 | CI test `DashboardCoverageContractTest` parses every panel SQL, extracts column names, diffs against the Retention Scope § A–G retained-field list |
| SC-008 | CI test `DashboardPrivacyContractTest` parses every per-record panel, asserts no panel selects a column without a display formatter |
| SC-009 | IT `IntegrityAlertSyntheticIT` injects orphan + asserts Alertmanager fires within 5min |
| SC-010 | Lint `MetricNamingLintTest` scans dashboard JSON for the deprecated plural `vouchers_*` substring; passes when count = 0 |
| SC-012 | IT `GrafanaRolePermissionIT` connects as `cashu_mint_grafana_ro`, attempts `SELECT customer_id FROM voucher_quote`, asserts `PSQLException` with state `42501` (insufficient privilege) |
