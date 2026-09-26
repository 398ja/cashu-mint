-- cashu-mint#494: issuance_record.keyset_id was VARCHAR(64), and a NUT-02 v2 keyset id is 66
-- characters (the 01 version byte plus a 32-byte SHA-256, in hex).
--
-- The row is written AFTER the outputs are signed, as the ledger entry that closes a quote
-- ISSUING -> ISSUED. Once the mint rotated onto a v2 keyset the insert failed every time, so
-- every regular mint left its quote stranded in ISSUING with the signatures already produced:
--
--   * the wallet saw a 500 on its first POST /v1/mint/bolt11,
--   * its retry found the quote no longer PAID and got 20005 issuance_in_progress,
--   * the status route kept reporting the quote PAID, since ISSUING is not ISSUED.
--
-- On staging no regular issuance had been recorded since 2026-08-31. blind_signature already
-- sizes the column at 66, and cashu-vault widened its own keyset id column the same way
-- (cashu-vault V8).
--
-- Widening a VARCHAR is a catalogue-only change in PostgreSQL and in H2: no table rewrite.

ALTER TABLE issuance_record ALTER COLUMN keyset_id TYPE VARCHAR(66);
