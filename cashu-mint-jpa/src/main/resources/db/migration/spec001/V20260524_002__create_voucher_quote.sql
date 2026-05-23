-- Spec 003 — durable voucher quote record. Sibling table to mint_quote
-- (research R1 — no JOINED inheritance because the schemas barely
-- overlap). quote_id namespace MUST stay disjoint from
-- mint_quote.quote_id; this is enforced by the quote-creation path
-- and asserted by an integration test.
--
-- funding_id is the FK to voucher_funding (V20260524_001). NULL while
-- the quote is UNFUNDED; set on the UNFUNDED → FUNDED CAS.
--
-- idempotency_key is partial-UNIQUE so two voucher quotes never collide
-- on the same key. request_hash is the SHA-256 of the canonical
-- request body — paired with idempotency_key it lets the idempotency
-- filter distinguish a true retry (same hash ⇒ replay cache) from a
-- tamper attempt (same key, different hash ⇒ 409).
--
-- Reference: data-model.md § VoucherQuote.

CREATE TABLE voucher_quote (
    quote_id         VARCHAR(64)              NOT NULL,
    voucher_type     VARCHAR(32)              NOT NULL,
    face_value       BIGINT                   NOT NULL,
    charged_amount   BIGINT                   NOT NULL,
    fee              BIGINT                   NOT NULL,
    unit             VARCHAR(16)              NOT NULL,
    merchant_id      VARCHAR(255),
    customer_id      VARCHAR(255),
    funding_id       VARCHAR(64),
    lifecycle_state  VARCHAR(16)              NOT NULL DEFAULT 'UNFUNDED',
    idempotency_key  VARCHAR(255),
    request_hash     VARCHAR(64)              NOT NULL,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    version          BIGINT                   NOT NULL DEFAULT 0,
    CONSTRAINT voucher_quote_pk PRIMARY KEY (quote_id),
    CONSTRAINT voucher_quote_face_value_positive_chk CHECK (face_value > 0),
    CONSTRAINT voucher_quote_charged_amount_positive_chk CHECK (charged_amount > 0),
    CONSTRAINT voucher_quote_fee_nonneg_chk CHECK (fee >= 0),
    CONSTRAINT voucher_quote_lifecycle_state_chk CHECK (lifecycle_state IN (
        'UNFUNDED', 'FUNDED', 'ISSUING', 'ISSUED', 'EXPIRED', 'FAILED'
    )),
    CONSTRAINT voucher_quote_funding_fk FOREIGN KEY (funding_id)
        REFERENCES voucher_funding (funding_id)
);

CREATE UNIQUE INDEX voucher_quote_idempotency_key_uk
    ON voucher_quote (idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE INDEX voucher_quote_lifecycle_state_idx ON voucher_quote (lifecycle_state);
CREATE INDEX voucher_quote_merchant_id_idx     ON voucher_quote (merchant_id);
CREATE INDEX voucher_quote_funding_id_idx      ON voucher_quote (funding_id);

-- Envers shadow.
CREATE TABLE voucher_quote_aud (
    quote_id         VARCHAR(64)              NOT NULL,
    rev              INTEGER                  NOT NULL,
    revtype          SMALLINT,
    voucher_type     VARCHAR(32),
    face_value       BIGINT,
    charged_amount   BIGINT,
    fee              BIGINT,
    unit             VARCHAR(16),
    merchant_id      VARCHAR(255),
    customer_id      VARCHAR(255),
    funding_id       VARCHAR(64),
    lifecycle_state  VARCHAR(16),
    idempotency_key  VARCHAR(255),
    request_hash     VARCHAR(64),
    created_at       TIMESTAMP WITH TIME ZONE,
    updated_at       TIMESTAMP WITH TIME ZONE,
    CONSTRAINT voucher_quote_aud_pk PRIMARY KEY (quote_id, rev),
    CONSTRAINT voucher_quote_aud_revinfo_fk FOREIGN KEY (rev) REFERENCES revinfo (rev)
);

CREATE INDEX voucher_quote_aud_rev_idx ON voucher_quote_aud (rev);
