-- cashu-mint#540 review: one row per declared redemption swap.
--
-- voucher_redemption holds the running total per coupon. This table records
-- which swap each part of that total came from, keyed by the swap's NUT-19
-- request fingerprint, so that:
--
--   * a NUT-19 retry of the same request (a lost reply, a process death
--     between the claim and the signature) reuses its claim instead of adding
--     a second one, which in enforce would refuse an honest full-cap redemption
--     (review §2a);
--   * the swap hold sweeper can give back a claim whose hold never began
--     signing (hold_id, state CLAIMED, hold phase HELD) (review §2a);
--   * a redeemer can release exactly what one of its own swaps declared when
--     the credit is undone (refund, reversal, downstream failure), and never
--     more, and only once (review §1.2a). redeemer_digest is the SHA-256 of
--     the token that declared it, so a release is honoured only for that
--     service. The token itself is never stored.
--
-- state:
--   CLAIMED     counted in voucher_redemption.redeemed_sats
--   GIVEN_BACK  the mint gave it back: the swap signed nothing
--   RELEASED    the redeemer gave it back: the credit was undone
--
-- Additive: no backfill, no lock on any existing table.

CREATE TABLE IF NOT EXISTS voucher_redemption_claim (
    request_fingerprint VARCHAR(64)              NOT NULL,
    voucher_id          VARCHAR(64)              NOT NULL,
    sats                BIGINT                   NOT NULL,
    redeemer_digest     VARCHAR(64)              NOT NULL,
    hold_id             VARCHAR(64),
    state               VARCHAR(16)              NOT NULL,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT voucher_redemption_claim_pk PRIMARY KEY (request_fingerprint),
    CONSTRAINT voucher_redemption_claim_sats_positive_chk CHECK (sats > 0),
    CONSTRAINT voucher_redemption_claim_state_chk CHECK (state IN ('CLAIMED', 'GIVEN_BACK', 'RELEASED'))
);

CREATE INDEX IF NOT EXISTS ix_voucher_redemption_claim_hold
    ON voucher_redemption_claim (hold_id) WHERE state = 'CLAIMED';

CREATE INDEX IF NOT EXISTS ix_voucher_redemption_claim_voucher
    ON voucher_redemption_claim (voucher_id);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'cashu_mint_grafana_ro') THEN
        GRANT SELECT ON voucher_redemption_claim TO cashu_mint_grafana_ro;
    END IF;
END
$$;
