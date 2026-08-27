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

-- NOT NULL alone would still admit '' or a truncated key, and a row like that is an
-- account nobody can sign in as that nonetheless holds roles. Uniqueness already comes
-- from V14's idx_admin_users_pubkey — this pins the shape.
DELETE FROM admin_users WHERE NOT REGEXP_LIKE(pubkey, '^[0-9a-f]{64}$');

ALTER TABLE admin_users ADD CONSTRAINT ck_admin_users_pubkey_hex
    CHECK (REGEXP_LIKE(pubkey, '^[0-9a-f]{64}$'));
