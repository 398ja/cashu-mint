-- Spec 001 — Mint Quote Amount Binding and Webhook Integrity
-- Durable record of a NUT-04 quote authorisation. Source of truth for the
-- lifecycle state machine. Envers audit-tracked.

CREATE TABLE mint_quote (
    quote_id         VARCHAR(64)              NOT NULL,
    amount           BIGINT                   NOT NULL,
    unit             VARCHAR(16)              NOT NULL,
    mint_url         TEXT                     NOT NULL,
    payment_method   VARCHAR(32)              NOT NULL,
    invoice_id       VARCHAR(255),
    lifecycle_state  VARCHAR(16)              NOT NULL DEFAULT 'UNPAID',
    request_hash     VARCHAR(64)                 NOT NULL,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    version          BIGINT                   NOT NULL DEFAULT 0,
    CONSTRAINT mint_quote_pk PRIMARY KEY (quote_id),
    CONSTRAINT mint_quote_amount_positive_chk CHECK (amount > 0),
    CONSTRAINT mint_quote_lifecycle_state_chk CHECK (lifecycle_state IN (
        'UNPAID', 'PENDING', 'PAID', 'ISSUING', 'ISSUED', 'EXPIRED', 'FAILED'
    ))
);

CREATE INDEX mint_quote_lifecycle_state_idx ON mint_quote (lifecycle_state);
CREATE INDEX mint_quote_created_at_idx      ON mint_quote (created_at);

-- Envers revision metadata (shared with future audit-tracked entities).
CREATE TABLE revinfo (
    rev      INTEGER NOT NULL,
    revtstmp BIGINT,
    CONSTRAINT revinfo_pk PRIMARY KEY (rev)
);

CREATE SEQUENCE revinfo_seq START WITH 1 INCREMENT BY 50;

-- Audit shadow for mint_quote. Hibernate Envers writes here on every revision.
CREATE TABLE mint_quote_aud (
    quote_id         VARCHAR(64)              NOT NULL,
    rev              INTEGER                  NOT NULL,
    revtype          SMALLINT,
    amount           BIGINT,
    unit             VARCHAR(16),
    mint_url         TEXT,
    payment_method   VARCHAR(32),
    invoice_id       VARCHAR(255),
    lifecycle_state  VARCHAR(16),
    request_hash     VARCHAR(64),
    created_at       TIMESTAMP WITH TIME ZONE,
    updated_at       TIMESTAMP WITH TIME ZONE,
    CONSTRAINT mint_quote_aud_pk PRIMARY KEY (quote_id, rev),
    CONSTRAINT mint_quote_aud_revinfo_fk FOREIGN KEY (rev) REFERENCES revinfo (rev)
);

CREATE INDEX mint_quote_aud_rev_idx ON mint_quote_aud (rev);
