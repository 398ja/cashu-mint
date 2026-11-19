-- cashu-mint#551: per-keyset redemption cutoff.
--
-- An archived keyset keeps redeeming indefinitely (ADR-0004). A row here ends that on a date:
-- from redeem_until on, swap and melt refuse the keyset's proofs with 12003 keyset_expired and
-- /v1/keysets and /v1/keys stop advertising it. Durable for the reason suspension is (ADR-0007):
-- a mint that restarts and redeems a retired keyset again is the failure the cutoff prevents.
--
-- One row per keyset, replaced while the cutoff is still ahead. Every change, including the
-- first, is appended to keyset_redemption_cutoff_history, which is never updated or deleted, so
-- the record answers both "when does it stop" and "who moved the date, and when".
CREATE TABLE IF NOT EXISTS keyset_redemption_cutoff (
    keyset_id    VARCHAR(66)              NOT NULL,
    redeem_until TIMESTAMP WITH TIME ZONE NOT NULL,
    reason       TEXT,
    set_by       VARCHAR(255),
    set_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_keyset_redemption_cutoff PRIMARY KEY (keyset_id)
);

CREATE TABLE IF NOT EXISTS keyset_redemption_cutoff_history (
    id           BIGSERIAL                NOT NULL,
    keyset_id    VARCHAR(66)              NOT NULL,
    redeem_until TIMESTAMP WITH TIME ZONE NOT NULL,
    reason       TEXT,
    set_by       VARCHAR(255),
    set_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_keyset_redemption_cutoff_history PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_keyset_redemption_cutoff_history_keyset
    ON keyset_redemption_cutoff_history (keyset_id, set_at);
