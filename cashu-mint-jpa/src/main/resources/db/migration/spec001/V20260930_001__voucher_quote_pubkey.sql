-- NUT-20 for voucher quotes (cashu-mint#529).
--
-- V20260829_002 let a regular mint quote be locked to a public key. A voucher
-- quote could not be, so once funded it was a bearer claim: anyone who learned
-- its id could POST /v1/mint/{method} with their own outputs and take the
-- voucher's face value. Voucher quote ids appear in gateway responses, logs and
-- gateway tables until the voucher is minted.
--
-- Nullable because locking is optional in the spec and every existing voucher
-- quote predates it. A null pubkey means an unlocked quote, which behaves
-- exactly as before, so applying this changes no behaviour on its own.
--
-- VARCHAR(66): a 33-byte compressed secp256k1 key, hex-encoded, the same width
-- as mint_quote.pubkey.

ALTER TABLE voucher_quote
    ADD COLUMN pubkey VARCHAR(66);

COMMENT ON COLUMN voucher_quote.pubkey IS
    'NUT-20: compressed secp256k1 key the voucher quote is locked to. '
    'NULL for an unlocked quote, which anyone holding the quote id can mint.';

-- VoucherQuoteEntity is @Audited, so Envers writes every audited column to the
-- shadow table too. Without it here the schema validator fails the boot, as it
-- did for original_token_amount (V20260601_008).
ALTER TABLE voucher_quote_aud
    ADD COLUMN pubkey VARCHAR(66);

COMMENT ON COLUMN voucher_quote_aud.pubkey IS
    'NUT-20: Envers shadow of voucher_quote.pubkey. '
    'NULL for revisions written before V20260930_001.';
