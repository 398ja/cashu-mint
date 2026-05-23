-- Spec 003 — durable Idempotency-Key cache for voucher endpoints. The
-- cache outlasts a JVM restart (research R10): same key + same
-- request_hash returns the cached response; same key + different
-- request_hash is rejected as a tamper signal.
--
-- Scoping: (idempotency_key, principal_id) is the PK so two principals
-- can use the same key without colliding. expires_at is indexed for
-- the TTL sweep job.

CREATE TABLE voucher_idempotency_key (
    idempotency_key    VARCHAR(255)             NOT NULL,
    principal_id       VARCHAR(255)             NOT NULL,
    request_hash       VARCHAR(64)              NOT NULL,
    response_status    INTEGER                  NOT NULL,
    response_body_json TEXT                     NOT NULL,
    expires_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT voucher_idempotency_key_pk PRIMARY KEY (idempotency_key, principal_id)
);

CREATE INDEX voucher_idempotency_key_expires_idx
    ON voucher_idempotency_key (expires_at);
