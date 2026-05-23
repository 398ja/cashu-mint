-- Spec 003 — append-only join row from voucher_quote → voucher_funding →
-- spec-001 issuance_record. Answers the FR-005 audit query
-- "given proof X, return its funding source" in one indexed JOIN.
--
-- The PK is voucher_quote_id (one issuance per voucher quote). The
-- issuance_id mirrors the spec-001 issuance_record.quote_id; the
-- namespace invariant (R1) means that single column unambiguously
-- selects the receipt row in either table.
--
-- outputs_hash is replicated from issuance_record for fast voucher
-- audits without joining; the parent IssuanceRecord row remains the
-- source of truth (the daily SC-001 reconciliation cross-checks both).

CREATE TABLE voucher_issuance (
    voucher_quote_id VARCHAR(64)              NOT NULL,
    funding_id       VARCHAR(64)              NOT NULL,
    issuance_id      VARCHAR(64)              NOT NULL,
    outputs_hash     VARCHAR(64)              NOT NULL,
    issued_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT voucher_issuance_pk PRIMARY KEY (voucher_quote_id),
    CONSTRAINT voucher_issuance_quote_fk FOREIGN KEY (voucher_quote_id)
        REFERENCES voucher_quote (quote_id),
    CONSTRAINT voucher_issuance_funding_fk FOREIGN KEY (funding_id)
        REFERENCES voucher_funding (funding_id)
);

CREATE INDEX voucher_issuance_funding_idx  ON voucher_issuance (funding_id);
CREATE INDEX voucher_issuance_issuance_idx ON voucher_issuance (issuance_id);
