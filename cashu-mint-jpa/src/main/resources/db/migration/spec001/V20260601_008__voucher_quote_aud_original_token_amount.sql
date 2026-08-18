-- Spec 035 follow-up — V20260601_007 added original_token_amount to
-- voucher_quote but not to its Envers shadow voucher_quote_aud.
--
-- VoucherQuoteEntity is @Audited at class level, so Envers maps every
-- audited property onto the _aud table. Hibernate's schema validator
-- therefore expects the column on both, and without it
-- mintEntityManagerFactory fails to build on boot:
--
--   Schema-validation: missing column [original_token_amount]
--                      in table [voucher_quote_AUD]
--
-- which takes the whole application context down whenever
-- cashu.mint.jpa.enabled=true. Every sibling voucher migration
-- (V20260601_005, V20260601_006) updates the _aud shadow alongside the
-- live table; 007 is the one that did not.
--
-- Nullable, matching the live column and the rest of voucher_quote_aud
-- (Envers shadows carry no NOT NULL beyond the PK). No backfill: audit
-- revisions written before 007 genuinely had no captured value, and
-- NULL is already the documented "legacy / unavailable" marker that the
-- provenance endpoint surfaces as issuance_ratio: null.

ALTER TABLE voucher_quote_aud
    ADD COLUMN original_token_amount BIGINT NULL;

COMMENT ON COLUMN voucher_quote_aud.original_token_amount IS
    'Spec 035 — Envers shadow of voucher_quote.original_token_amount. '
    'NULL for revisions written before V20260601_007.';
