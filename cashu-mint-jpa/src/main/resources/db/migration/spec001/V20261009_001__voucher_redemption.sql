-- cashu-mint#540: per-voucher redemption ledger.
--
-- An unlocked coupon's signed blob can be copied onto fresh plain sats: swap
-- outputs are blinded, so the mint cannot tell, and every copy passes the
-- signature, issuer-binding and double-spend checks. One coupon could be
-- credited without limit.
--
-- A redeemer that credits a coupon declares it on the swap that takes it
-- (X-Cashu-Voucher-Redemption). This table holds, per voucher id, what has
-- been credited (redeemed_sats) against what was issued (cap_sats, derived from
-- the coupon's signed face_value / issuance_ratio). In enforce mode a claim
-- that would take redeemed_sats past cap_sats is refused by one conditional
-- UPDATE, which Postgres row-locks, so concurrent claims on one voucher are
-- atomic across mint instances.
--
-- cap_sats is NULL when the coupon's terms do not state it (pre-signature
-- legacy blobs). Those are recorded and never refused.
--
-- Additive: no backfill, no lock on any existing table.

CREATE TABLE IF NOT EXISTS voucher_redemption (
    voucher_id    VARCHAR(64)              NOT NULL,
    cap_sats      BIGINT,
    redeemed_sats BIGINT                   NOT NULL DEFAULT 0,
    overspent     BOOLEAN                  NOT NULL DEFAULT false,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT voucher_redemption_pk PRIMARY KEY (voucher_id),
    CONSTRAINT voucher_redemption_cap_positive_chk CHECK (cap_sats IS NULL OR cap_sats > 0),
    CONSTRAINT voucher_redemption_redeemed_nonneg_chk CHECK (redeemed_sats >= 0)
);

-- Read-only access for the operator dashboards, where the role exists.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'cashu_mint_grafana_ro') THEN
        GRANT SELECT ON voucher_redemption TO cashu_mint_grafana_ro;
    END IF;
END
$$;
