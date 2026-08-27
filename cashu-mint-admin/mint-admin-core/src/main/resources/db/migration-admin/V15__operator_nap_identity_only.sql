-- Operators authenticate only through a NAP session (issue #373). The shared
-- token, the per-operator credential and the reset workflow are gone from the
-- code, so their columns go too: a credential column nothing writes is a
-- credential column someone eventually authenticates against.

DROP INDEX IF EXISTS idx_admin_users_credential_hash;

ALTER TABLE admin_users DROP COLUMN credential_hash;
ALTER TABLE admin_users DROP COLUMN reset_count;
ALTER TABLE admin_users DROP COLUMN reset_requested_at;

-- The public key is now the only way in. Rows without one are unreachable, and
-- there is no deployment with real Operators to migrate, so they are removed
-- rather than carried forward as accounts nobody can sign in as.
DELETE FROM admin_users WHERE pubkey IS NULL;

ALTER TABLE admin_users ALTER COLUMN pubkey SET NOT NULL;
