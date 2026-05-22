-- Spec 001 — Append-only record of every payment webhook the mint receives.
-- Keyed by (provider, provider_event_id) per FR-006. No UPDATEs from application code.

CREATE TABLE webhook_event (
    provider           VARCHAR(64)              NOT NULL,
    provider_event_id  VARCHAR(255)             NOT NULL,
    quote_id           VARCHAR(64)              NOT NULL,
    amount             BIGINT                   NOT NULL,
    unit               VARCHAR(16)              NOT NULL,
    payment_method     VARCHAR(32)              NOT NULL,
    signature_digest   VARCHAR(64),
    outcome            VARCHAR(32)              NOT NULL,
    received_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    raw_body_compressed BYTEA,
    CONSTRAINT webhook_event_pk PRIMARY KEY (provider, provider_event_id),
    CONSTRAINT webhook_event_amount_positive_chk CHECK (amount > 0),
    CONSTRAINT webhook_event_quote_fk FOREIGN KEY (quote_id)
        REFERENCES mint_quote (quote_id),
    CONSTRAINT webhook_event_outcome_chk CHECK (outcome IN (
        'accepted',
        'amount_mismatch',
        'unit_mismatch',
        'method_mismatch',
        'duplicate',
        'tamper',
        'unsigned_rejected',
        'signature_invalid',
        'expired',
        'noop',
        'orphan'
    ))
);

CREATE INDEX webhook_event_quote_id_idx    ON webhook_event (quote_id);
CREATE INDEX webhook_event_outcome_idx     ON webhook_event (outcome);
CREATE INDEX webhook_event_received_at_idx ON webhook_event (received_at);
