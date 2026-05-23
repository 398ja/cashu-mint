-- Spec 004 T034 / research R5a + R5c — drop columns flagged as
-- minimisation candidates in spec.md § Retention Scope § H.
--
-- merchant_debit_funding.merchant_ledger_balance_after — research
-- R5a: optional forensic snapshot with no operator consumer; the
-- merchant's own ledger is the source of truth. Storing a stale
-- snapshot on the mint side duplicates data and adds an unnecessary
-- custody surface (merchant financial position).
--
-- voucher_issuance.issuance_id — research R5c: denormalised mirror
-- of voucher_quote_id with no current consumer. Pure forward
-- compatibility for a hypothetical shared issuance ledger; YAGNI
-- says drop. Easy to re-add later if the shared ledger materialises.

ALTER TABLE merchant_debit_funding
    DROP COLUMN merchant_ledger_balance_after;

ALTER TABLE merchant_debit_funding_aud
    DROP COLUMN merchant_ledger_balance_after;

ALTER TABLE voucher_issuance
    DROP COLUMN issuance_id;

-- Note: voucher_issuance is NOT Envers-audited (append-only by
-- design — spec 003 § VoucherIssuance), so no _aud variant to drop.

DROP INDEX IF EXISTS voucher_issuance_issuance_idx;
