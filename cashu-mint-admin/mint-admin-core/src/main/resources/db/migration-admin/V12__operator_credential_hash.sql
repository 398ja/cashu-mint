-- Operators authenticate with their own credential (ADR-0005). The column
-- previously held a predictable plaintext token (accountId + "-reset-" + n);
-- it now holds the SHA-256 hash of a randomly generated credential, and the
-- plaintext is returned to the caller exactly once at issue time.

ALTER TABLE admin_users RENAME COLUMN reset_token TO credential_hash;

UPDATE admin_users SET credential_hash = NULL;

-- A plain unique index: both PostgreSQL and H2 permit repeated NULLs, so
-- operators without an issued credential do not collide.
CREATE UNIQUE INDEX idx_admin_users_credential_hash ON admin_users (credential_hash);
