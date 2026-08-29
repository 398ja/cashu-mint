-- NUT-20 lets a wallet lock a mint quote to a public key, so only the holder of
-- the matching private key can mint its ecash.
--
-- Without it a quote id is a bearer token: NUT-04 warns that anyone who learns
-- the id of a paid quote can take the ecash, and a quote id travels through
-- logs, webhooks, traces and the admin API. The window is between payment and
-- issuance.
--
-- Nullable because locking is optional in the spec and every existing quote
-- predates it. A null pubkey means an unlocked quote, which behaves exactly as
-- before, so applying this changes no behaviour on its own.

ALTER TABLE mint_quote
    ADD COLUMN pubkey VARCHAR(66);

-- The Envers audit table mirrors the entity, so a column missing here fails
-- every write to a quote rather than just the audit of one.
ALTER TABLE mint_quote_aud
    ADD COLUMN pubkey VARCHAR(66);
