-- A swap signs its outputs before it can spend its inputs, and the two writes
-- cannot share a transaction because the vault is reached over its REST API.
-- The proof rows already carry the hold id that closes that gap. This table
-- answers the two questions those rows cannot, once a process has died:
-- which holds are unresolved, and whether each had begun signing.
--
-- The phase is the load-bearing column. A hold stranded before signing is
-- released; one stranded after must be committed, because its outputs may
-- already be redeemable. The two resolve in opposite directions and a held
-- proof looks identical either way, so the distinction has to be durable.
--
-- Rows are kept after they reach a terminal phase rather than deleted: the
-- record of a hold committed without signatures is what an operator needs to
-- make the affected wallet whole.

CREATE TABLE IF NOT EXISTS swap_hold (
    hold_id     VARCHAR(64)  NOT NULL,
    phase       VARCHAR(16)  NOT NULL,
    input_count INTEGER      NOT NULL,
    created_at  TIMESTAMP    NOT NULL,
    updated_at  TIMESTAMP    NOT NULL,
    CONSTRAINT pk_swap_hold PRIMARY KEY (hold_id)
);

-- The reconciler's only query: unresolved holds that have stopped moving.
CREATE INDEX IF NOT EXISTS ix_swap_hold_phase_updated
    ON swap_hold (phase, updated_at);
