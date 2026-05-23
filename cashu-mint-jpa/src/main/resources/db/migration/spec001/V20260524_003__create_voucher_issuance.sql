-- Spec 003 — append-only join row from voucher_quote → voucher_funding.
-- Answers the FR-005 audit query "given proof X, return its funding
-- source" in one indexed JOIN.
--
-- The PK is voucher_quote_id (one issuance per voucher quote).
--
-- issuance_id is a DENORMALISED MIRROR of voucher_quote_id, retained
-- for forward compatibility (e.g., a future shared issuance ledger
-- where both regular mint quotes and voucher quotes appear). It is
-- DELIBERATELY not declared as a FK because the spec-001
-- issuance_record table FKs its quote_id to mint_quote(quote_id), and
-- voucher quote_ids do NOT appear in mint_quote (research R1 namespace
-- invariant). MintTask's voucher branch writes voucher_issuance only;
-- it does NOT write to issuance_record for voucher quotes.
--
-- outputs_hash is captured here so voucher audits don't have to JOIN
-- back to any other table to obtain the blinded-output anchor.

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
