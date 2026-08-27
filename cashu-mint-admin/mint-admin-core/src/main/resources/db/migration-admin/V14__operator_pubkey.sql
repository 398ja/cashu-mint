-- NAP authenticates Operators by their Nostr public key (issue #372). The
-- column is nullable: every existing Operator predates NAP and has none until
-- the Super Administrator sets one, and an Operator without a pubkey simply
-- cannot complete a handshake.

ALTER TABLE admin_users ADD COLUMN pubkey TEXT;

-- Both PostgreSQL and H2 permit repeated NULLs in a unique index, so the
-- Operators without a key do not collide with each other.
CREATE UNIQUE INDEX idx_admin_users_pubkey ON admin_users (pubkey);
