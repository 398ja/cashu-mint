-- Issue #491: the durable record of every blind signature the mint has issued.
--
-- Before this table the record lived in process memory only, so a restart or a
-- second replica forgot which blinded messages had been signed. That re-opened
-- signing the same B_ twice and made NUT-09 restore return nothing for outputs
-- signed before the last restart.
--
-- b_ is the primary key, and the key is the whole defence: an INSERT for a B_
-- that is already here fails with a unique violation, which the adapter turns
-- into outputs_already_signed. Rows are never updated or deleted.
--
-- keyset_id is sized for NUT-02 v2 ids (33 bytes, 66 hex chars), not the
-- 16-char v1 ids.
--
-- amount allows zero. The zero-value IOU keyset (Dalia Phase 9) signs amount 0
-- markers, and they must be recorded too, or the same IOU output could be
-- signed twice. Negative amounts remain impossible.
--
-- The DLEQ proof (NUT-12) is stored when present so a restore returns the
-- signature exactly as first issued. Either both halves are present or neither.
--
-- source says why the signature exists, so issued value can be reconciled
-- against what backs it: MINT against a paid quote, SWAP against spent inputs,
-- MELT_CHANGE for the overpaid part of a melt fee reserve.

CREATE TABLE IF NOT EXISTS blind_signature (
    b_          VARCHAR(66)              NOT NULL,
    keyset_id   VARCHAR(66)              NOT NULL,
    amount      BIGINT                   NOT NULL,
    c_          VARCHAR(66)              NOT NULL,
    dleq_e      VARCHAR(64),
    dleq_s      VARCHAR(64),
    source      VARCHAR(16)              NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT blind_signature_pk PRIMARY KEY (b_),
    CONSTRAINT blind_signature_amount_non_negative_chk CHECK (amount >= 0),
    CONSTRAINT blind_signature_dleq_complete_chk CHECK ((dleq_e IS NULL) = (dleq_s IS NULL)),
    CONSTRAINT blind_signature_source_chk CHECK (source IN ('SWAP', 'MINT', 'MELT_CHANGE'))
);

-- The issued-amount invariant sums per keyset.
CREATE INDEX IF NOT EXISTS blind_signature_keyset_ix ON blind_signature (keyset_id);
