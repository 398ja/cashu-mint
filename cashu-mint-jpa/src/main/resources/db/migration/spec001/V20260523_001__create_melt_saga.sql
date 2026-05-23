-- Spec 002 — Melt path burn-first ordering and burn-amount check.
-- Durable record of a NUT-05 melt attempt. One row per quote; unique
-- across the lifetime of that quote (FR-005: concurrent melts for the
-- same quote_id are rejected with `melt_in_progress`).

CREATE TABLE melt_saga (
    melt_saga_id            VARCHAR(64)              NOT NULL,
    quote_id                VARCHAR(64)              NOT NULL,
    invoice_amount          BIGINT                   NOT NULL,
    exact_fee_reserve       BIGINT                   NOT NULL,
    asserted_fee_reserve    BIGINT,
    input_amount            BIGINT                   NOT NULL,
    proof_count             INTEGER                  NOT NULL,
    current_state           VARCHAR(32)              NOT NULL DEFAULT 'PROOFS_HELD',
    payment_hash            VARCHAR(255),
    provider                VARCHAR(64)              NOT NULL,
    provider_event_id       VARCHAR(255),
    payment_outcome_reason  TEXT,
    melt_response_cache     JSONB,
    change_outputs_hash     VARCHAR(64),
    change_signatures_json  JSONB,
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at              TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    version                 BIGINT                   NOT NULL DEFAULT 0,
    CONSTRAINT melt_saga_pk PRIMARY KEY (melt_saga_id),
    CONSTRAINT melt_saga_quote_id_uq UNIQUE (quote_id),
    CONSTRAINT melt_saga_invoice_amount_positive_chk CHECK (invoice_amount > 0),
    CONSTRAINT melt_saga_fee_reserve_nonneg_chk CHECK (exact_fee_reserve >= 0),
    CONSTRAINT melt_saga_input_amount_positive_chk CHECK (input_amount > 0),
    CONSTRAINT melt_saga_proof_count_positive_chk CHECK (proof_count > 0),
    CONSTRAINT melt_saga_current_state_chk CHECK (current_state IN (
        'PROOFS_HELD', 'PAYMENT_SENT', 'COMPLETED', 'FAILED',
        'PAYMENT_SENT_BURN_FAILED', 'PAYMENT_UNKNOWN'
    ))
);

CREATE INDEX melt_saga_current_state_idx     ON melt_saga (current_state);
CREATE INDEX melt_saga_created_at_idx        ON melt_saga (created_at);
CREATE INDEX melt_saga_provider_event_id_idx ON melt_saga (provider_event_id);

-- Envers audit shadow. Reuses the revinfo / revinfo_seq created by the
-- spec-001 V20260522_001__create_mint_quote.sql migration; if Envers
-- needs additional shadow columns Hibernate will create them at startup
-- via hbm2ddl auto-update; explicit creation here keeps Flyway's
-- baseline-on-migrate happy under schema validation.
CREATE TABLE melt_saga_aud (
    melt_saga_id            VARCHAR(64)              NOT NULL,
    rev                     INTEGER                  NOT NULL,
    revtype                 SMALLINT,
    current_state           VARCHAR(32),
    payment_hash            VARCHAR(255),
    provider_event_id       VARCHAR(255),
    updated_at              TIMESTAMP WITH TIME ZONE,
    CONSTRAINT melt_saga_aud_pk PRIMARY KEY (melt_saga_id, rev),
    CONSTRAINT melt_saga_aud_revinfo_fk FOREIGN KEY (rev) REFERENCES revinfo (rev)
);

CREATE INDEX melt_saga_aud_rev_idx ON melt_saga_aud (rev);
