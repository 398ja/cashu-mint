-- Spec 004 T031 / FR-003 / FR-010 — append-only audit log of
-- retention purge runs. Each row records when the daily purge job
-- ran, the retention cutoff it applied, and how many voucher_quote
-- rows + Envers _aud rows had their identity columns nullified.
--
-- FR-010's "purged on date X" marker: operators querying a row
-- whose identity is NULL can correlate to a row in this table by
-- matching the row's updated_at against retention_cutoff. If no
-- matching purge_log row exists, the original quote was anonymous
-- (FR-019).

CREATE TABLE voucher_quote_purge_log (
    purge_id          UUID                     NOT NULL,
    purged_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    retention_cutoff  TIMESTAMP WITH TIME ZONE NOT NULL,
    rows_purged       BIGINT                   NOT NULL,
    aud_rows_purged   BIGINT                   NOT NULL,
    duration_ms       BIGINT                   NOT NULL,
    CONSTRAINT voucher_quote_purge_log_pk PRIMARY KEY (purge_id)
);

CREATE INDEX voucher_quote_purge_log_purged_at_idx
    ON voucher_quote_purge_log (purged_at);
