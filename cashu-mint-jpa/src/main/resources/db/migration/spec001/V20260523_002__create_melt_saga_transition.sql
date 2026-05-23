-- Spec 002 — Append-only timeline of every melt-saga state transition.
-- One row per transition (including polling no-ops in PAYMENT_UNKNOWN).
-- No UPDATEs from application code.

CREATE TABLE melt_saga_transition (
    melt_saga_id  VARCHAR(64)              NOT NULL,
    seq           INTEGER                  NOT NULL,
    from_state    VARCHAR(32),
    to_state      VARCHAR(32)              NOT NULL,
    reason        TEXT,
    actor         VARCHAR(64)              NOT NULL,
    at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT melt_saga_transition_pk PRIMARY KEY (melt_saga_id, seq),
    CONSTRAINT melt_saga_transition_saga_fk FOREIGN KEY (melt_saga_id)
        REFERENCES melt_saga (melt_saga_id),
    CONSTRAINT melt_saga_transition_to_state_chk CHECK (to_state IN (
        'PROOFS_HELD', 'PAYMENT_SENT', 'COMPLETED', 'FAILED',
        'PAYMENT_SENT_BURN_FAILED', 'PAYMENT_UNKNOWN'
    )),
    CONSTRAINT melt_saga_transition_from_state_chk CHECK (
        from_state IS NULL OR from_state IN (
            'PROOFS_HELD', 'PAYMENT_SENT', 'COMPLETED', 'FAILED',
            'PAYMENT_SENT_BURN_FAILED', 'PAYMENT_UNKNOWN'
        )
    )
);

CREATE INDEX melt_saga_transition_at_idx ON melt_saga_transition (at);
