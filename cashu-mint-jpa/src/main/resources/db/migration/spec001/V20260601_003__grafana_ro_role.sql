-- Spec 004 T032 / FR-013 / Clarifications Q4 — dedicated read-only
-- PostgreSQL role for the Grafana data source. Defence in depth on
-- top of dashboard JSON review: an ad-hoc Grafana query that
-- selects an identity column fails at the DB layer with
-- "permission denied for column customer_id" rather than silently
-- exposing raw hashes.
--
-- Column-level GRANT SELECT enumerates exactly the columns Grafana
-- panels need. customer_id + merchant_id are deliberately omitted
-- from every GRANT — the hash bytes are present in the column but
-- the role cannot read them.
--
-- The role's password comes from the Flyway placeholder
-- ${grafana_ro_password} sourced from env CASHU_MINT_GRAFANA_RO_PASSWORD
-- (see quickstart.md § 2).

-- Idempotent role creation — Flyway re-runs only if the migration
-- hash changes, but in case of manual reset, the DO block prevents
-- a CREATE ROLE conflict.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'cashu_mint_grafana_ro') THEN
        EXECUTE format('CREATE ROLE cashu_mint_grafana_ro WITH LOGIN PASSWORD %L',
                       current_setting('flyway.placeholders.grafana_ro_password'));
    END IF;
END
$$;

-- voucher_quote: financial + state only; NO customer_id or merchant_id
GRANT SELECT (quote_id, voucher_type, face_value, charged_amount, fee, unit,
              funding_id, lifecycle_state, idempotency_key, request_hash,
              created_at, updated_at, version)
  ON voucher_quote TO cashu_mint_grafana_ro;

-- voucher_funding parent: financial + discriminator only
GRANT SELECT (funding_id, funding_source, amount, unit, created_at, version)
  ON voucher_funding TO cashu_mint_grafana_ro;

-- customer_payment_funding: provider anchors only; NO customer_id
GRANT SELECT (funding_id, provider, provider_event_id, webhook_event_quote_id)
  ON customer_payment_funding TO cashu_mint_grafana_ro;

-- merchant_debit_funding: ledger anchor only; NO merchant_id
-- (merchant_ledger_balance_after dropped in V20260601_005)
GRANT SELECT (funding_id, merchant_debit_id)
  ON merchant_debit_funding TO cashu_mint_grafana_ro;

-- merchant_iou_funding: IOU instrument only; NO merchant_id
-- (iou_terms_hash added in V20260601_006; old iou_terms TEXT not granted)
GRANT SELECT (funding_id, iou_id, iou_due_at, policy_profile)
  ON merchant_iou_funding TO cashu_mint_grafana_ro;

-- voucher_issuance: full read OK (no identity columns by design)
-- (issuance_id dropped in V20260601_005)
GRANT SELECT (voucher_quote_id, funding_id, outputs_hash, issued_at)
  ON voucher_issuance TO cashu_mint_grafana_ro;

-- Audit purge log: full read OK (no identity columns)
GRANT SELECT ON voucher_quote_purge_log TO cashu_mint_grafana_ro;

-- Backfill log: full read OK (no identity columns)
GRANT SELECT ON voucher_identity_backfill_log TO cashu_mint_grafana_ro;

-- DDL + writes: deliberately no GRANT. The role can only SELECT.
-- _aud Envers shadow tables: deliberately no GRANT. Operator
-- forensics goes via the FR-009 admin REST endpoint, not Grafana.
