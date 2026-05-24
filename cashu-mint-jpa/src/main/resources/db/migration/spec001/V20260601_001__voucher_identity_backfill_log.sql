-- Spec 004 T030 / FR-011 — append-only tracker for the boot-time
-- identity backfill job. The job hashes existing raw npubs into the
-- HMAC scheme defined by FR-002; this table records progress per
-- voucher table so a crash-and-resume picks up where it left off
-- (idempotent re-hash means re-running a chunk is safe but wasteful).
--
-- One row per voucher table (live and _aud variants). completed_at
-- IS NOT NULL means "this table has zero remaining raw npubs."

CREATE TABLE voucher_identity_backfill_log (
    table_name      VARCHAR(64)              NOT NULL,
    last_hashed_pk  VARCHAR(64),
    rows_hashed     BIGINT                   NOT NULL DEFAULT 0,
    started_at      TIMESTAMP WITH TIME ZONE,
    completed_at    TIMESTAMP WITH TIME ZONE,
    version         BIGINT                   NOT NULL DEFAULT 0,
    CONSTRAINT voucher_identity_backfill_log_pk PRIMARY KEY (table_name)
);
