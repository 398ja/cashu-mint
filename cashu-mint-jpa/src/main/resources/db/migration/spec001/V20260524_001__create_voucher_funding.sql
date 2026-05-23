-- Spec 003 — Voucher Quote Durability and Funding-Source Binding
-- Polymorphic funding record (JOINED inheritance): one parent table
-- plus three concrete subclass tables.
--
-- A voucher quote is only allowed to advance to ISSUED when its
-- funding_id resolves to a row in one of these tables. The discriminator
-- column funding_source selects which child table carries the variant
-- detail.
--
-- Reference: data-model.md § VoucherFunding (research R2 — JOINED beats
-- single-table because the per-variant fields don't overlap).

CREATE TABLE voucher_funding (
    funding_id       VARCHAR(64)              NOT NULL,
    funding_source   VARCHAR(32)              NOT NULL,
    amount           BIGINT                   NOT NULL,
    unit             VARCHAR(16)              NOT NULL,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    version          BIGINT                   NOT NULL DEFAULT 0,
    CONSTRAINT voucher_funding_pk PRIMARY KEY (funding_id),
    CONSTRAINT voucher_funding_amount_positive_chk CHECK (amount > 0),
    CONSTRAINT voucher_funding_source_chk CHECK (funding_source IN (
        'CUSTOMER_PAYMENT', 'MERCHANT_DEBIT', 'MERCHANT_IOU'
    ))
);

CREATE INDEX voucher_funding_source_idx ON voucher_funding (funding_source);

-- ---------------------------------------------------------------
-- Subclass: CustomerPaymentFunding
-- ---------------------------------------------------------------
CREATE TABLE customer_payment_funding (
    funding_id              VARCHAR(64)  NOT NULL,
    provider                VARCHAR(64)  NOT NULL,
    provider_event_id       VARCHAR(255) NOT NULL,
    webhook_event_quote_id  VARCHAR(64),
    customer_id             VARCHAR(255),
    CONSTRAINT customer_payment_funding_pk PRIMARY KEY (funding_id),
    CONSTRAINT customer_payment_funding_parent_fk FOREIGN KEY (funding_id)
        REFERENCES voucher_funding (funding_id) ON DELETE CASCADE,
    CONSTRAINT customer_payment_funding_provider_event_uk
        UNIQUE (provider, provider_event_id)
);

CREATE INDEX customer_payment_funding_customer_idx
    ON customer_payment_funding (customer_id);

-- ---------------------------------------------------------------
-- Subclass: MerchantDebitFunding
-- ---------------------------------------------------------------
CREATE TABLE merchant_debit_funding (
    funding_id                    VARCHAR(64)  NOT NULL,
    merchant_id                   VARCHAR(255) NOT NULL,
    merchant_debit_id             VARCHAR(255) NOT NULL,
    merchant_ledger_balance_after BIGINT,
    CONSTRAINT merchant_debit_funding_pk PRIMARY KEY (funding_id),
    CONSTRAINT merchant_debit_funding_parent_fk FOREIGN KEY (funding_id)
        REFERENCES voucher_funding (funding_id) ON DELETE CASCADE,
    CONSTRAINT merchant_debit_funding_debit_uk
        UNIQUE (merchant_id, merchant_debit_id)
);

CREATE INDEX merchant_debit_funding_merchant_idx
    ON merchant_debit_funding (merchant_id);

-- ---------------------------------------------------------------
-- Subclass: MerchantIouFunding
-- ---------------------------------------------------------------
CREATE TABLE merchant_iou_funding (
    funding_id     VARCHAR(64)              NOT NULL,
    merchant_id    VARCHAR(255)             NOT NULL,
    iou_id         VARCHAR(255)             NOT NULL,
    iou_terms      TEXT,
    iou_due_at     TIMESTAMP WITH TIME ZONE,
    policy_profile VARCHAR(16)              NOT NULL,
    CONSTRAINT merchant_iou_funding_pk PRIMARY KEY (funding_id),
    CONSTRAINT merchant_iou_funding_parent_fk FOREIGN KEY (funding_id)
        REFERENCES voucher_funding (funding_id) ON DELETE CASCADE,
    CONSTRAINT merchant_iou_funding_iou_uk
        UNIQUE (merchant_id, iou_id)
);

CREATE INDEX merchant_iou_funding_due_idx
    ON merchant_iou_funding (iou_due_at);

-- ---------------------------------------------------------------
-- Envers audit shadows (parent + 3 children). revinfo table created
-- by spec 001's V20260522_001.
-- ---------------------------------------------------------------
CREATE TABLE voucher_funding_aud (
    funding_id      VARCHAR(64)              NOT NULL,
    rev             INTEGER                  NOT NULL,
    revtype         SMALLINT,
    funding_source  VARCHAR(32),
    amount          BIGINT,
    unit            VARCHAR(16),
    created_at      TIMESTAMP WITH TIME ZONE,
    CONSTRAINT voucher_funding_aud_pk PRIMARY KEY (funding_id, rev),
    CONSTRAINT voucher_funding_aud_revinfo_fk FOREIGN KEY (rev) REFERENCES revinfo (rev)
);

CREATE INDEX voucher_funding_aud_rev_idx ON voucher_funding_aud (rev);

CREATE TABLE customer_payment_funding_aud (
    funding_id              VARCHAR(64) NOT NULL,
    rev                     INTEGER     NOT NULL,
    revtype                 SMALLINT,
    provider                VARCHAR(64),
    provider_event_id       VARCHAR(255),
    webhook_event_quote_id  VARCHAR(64),
    customer_id             VARCHAR(255),
    CONSTRAINT customer_payment_funding_aud_pk PRIMARY KEY (funding_id, rev),
    CONSTRAINT customer_payment_funding_aud_revinfo_fk FOREIGN KEY (rev) REFERENCES revinfo (rev)
);

CREATE INDEX customer_payment_funding_aud_rev_idx ON customer_payment_funding_aud (rev);

CREATE TABLE merchant_debit_funding_aud (
    funding_id                    VARCHAR(64) NOT NULL,
    rev                           INTEGER     NOT NULL,
    revtype                       SMALLINT,
    merchant_id                   VARCHAR(255),
    merchant_debit_id             VARCHAR(255),
    merchant_ledger_balance_after BIGINT,
    CONSTRAINT merchant_debit_funding_aud_pk PRIMARY KEY (funding_id, rev),
    CONSTRAINT merchant_debit_funding_aud_revinfo_fk FOREIGN KEY (rev) REFERENCES revinfo (rev)
);

CREATE INDEX merchant_debit_funding_aud_rev_idx ON merchant_debit_funding_aud (rev);

CREATE TABLE merchant_iou_funding_aud (
    funding_id     VARCHAR(64) NOT NULL,
    rev            INTEGER     NOT NULL,
    revtype        SMALLINT,
    merchant_id    VARCHAR(255),
    iou_id         VARCHAR(255),
    iou_terms      TEXT,
    iou_due_at     TIMESTAMP WITH TIME ZONE,
    policy_profile VARCHAR(16),
    CONSTRAINT merchant_iou_funding_aud_pk PRIMARY KEY (funding_id, rev),
    CONSTRAINT merchant_iou_funding_aud_revinfo_fk FOREIGN KEY (rev) REFERENCES revinfo (rev)
);

CREATE INDEX merchant_iou_funding_aud_rev_idx ON merchant_iou_funding_aud (rev);
