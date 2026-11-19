-- Records which voucher a voucher quote was created for.
--
-- A gateway checking a voucher's issuance needs to know that the outputs the
-- mint signed under a quote were signed for THAT voucher. Until now the mint
-- kept no link from a voucher quote to a voucher: the issuing service could
-- name any paid quote. The mint now records the voucher id at quote creation,
-- before the invoice exists, so the binding is the mint's own record.
--
-- Additive only. Nullable because the binding is optional and every existing
-- quote predates it: a NULL voucher_id is an unbound quote, which behaves
-- exactly as before. The unique index is partial so any number of unbound
-- quotes may coexist, while one voucher id can name at most one quote.
--
-- VARCHAR(64) leaves room beyond the 36-character UUID issuers use today.

ALTER TABLE voucher_quote
    ADD COLUMN voucher_id VARCHAR(64);

COMMENT ON COLUMN voucher_quote.voucher_id IS
    'Voucher this quote was created for, recorded by the mint at quote creation. '
    'NULL for a quote created without a binding. Immutable and unique.';

CREATE UNIQUE INDEX voucher_quote_voucher_id_uk
    ON voucher_quote (voucher_id)
    WHERE voucher_id IS NOT NULL;

-- VoucherQuoteEntity is @Audited, so the Envers shadow table needs the column
-- too, or the schema validator fails the boot (see V20260930_001).
ALTER TABLE voucher_quote_aud
    ADD COLUMN voucher_id VARCHAR(64);

COMMENT ON COLUMN voucher_quote_aud.voucher_id IS
    'Envers shadow of voucher_quote.voucher_id. NULL for revisions written before V20261119_002.';
