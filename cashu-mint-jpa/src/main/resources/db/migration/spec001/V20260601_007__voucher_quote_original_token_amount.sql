-- Spec 035 — partial-spend display: surface issuance_ratio on the
-- receive-side endpoints so the wallet's `derived = token_amount ×
-- issuance_ratio` correction can fire for partial-spend tokens.
--
-- original_token_amount captures the sum of blinded message amounts at
-- voucher-quote issuance time. The wallet needs the ORIGINAL value (not
-- the residual after partial spends) to compute the ratio:
--
--   issuance_ratio = face_value / original_token_amount
--
-- Nullable on purpose. Rows issued before this migration are legacy and
-- have no captured value; the provenance endpoint returns
-- issuance_ratio: null for them so the wallet falls back to the embedded
-- face_value path (spec-035 iter-6 frontend already handles null).
--
-- No backfill from voucher_issuance.outputs_hash — recomputing the
-- output sum requires re-fetching proofs from the vault, which is too
-- brittle for a schema migration. NULL cleanly means "legacy /
-- unavailable" and is the documented fallback.
--
-- Set at issuance time by MintTask before the FUNDED → ISSUED CAS,
-- atomic with the lifecycle close via VoucherQuoteRepository.recordIssuance.

ALTER TABLE voucher_quote
    ADD COLUMN original_token_amount BIGINT NULL;

COMMENT ON COLUMN voucher_quote.original_token_amount IS
    'Spec 035 — sum of blinded message amounts at voucher issuance time. '
    'Used to compute issuance_ratio = face_value / original_token_amount '
    'for partial-spend display on the receive side. NULL for legacy rows '
    'issued before this migration.';
