-- Spec 001 — Append-only ledger of every successful mint issuance.
-- Keyed by quote_id for idempotent NUT-19 replay. No UPDATEs from application code.

CREATE TABLE issuance_record (
    quote_id        VARCHAR(64)              NOT NULL,
    outputs_hash    VARCHAR(64)                 NOT NULL,
    signatures_json JSONB                    NOT NULL,
    keyset_id       VARCHAR(64)              NOT NULL,
    total_amount    BIGINT                   NOT NULL,
    issued_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT issuance_record_pk PRIMARY KEY (quote_id),
    CONSTRAINT issuance_record_total_amount_positive_chk CHECK (total_amount > 0),
    CONSTRAINT issuance_record_quote_fk FOREIGN KEY (quote_id)
        REFERENCES mint_quote (quote_id),
    CONSTRAINT issuance_record_outputs_hash_unique UNIQUE (quote_id, outputs_hash)
);

CREATE INDEX issuance_record_issued_at_idx ON issuance_record (issued_at);
