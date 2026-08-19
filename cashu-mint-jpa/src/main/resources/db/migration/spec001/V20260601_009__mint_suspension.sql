-- A suspended mint issues no new tokens but still honours swaps and melts
-- (ADR-0006). The state is durable because a mint that restarts and resumes
-- issuing is the failure suspending is meant to prevent (ADR-0007).
--
-- A row exists only while the mint is suspended, so the ordinary case is a
-- primary-key miss on the issuance path.
CREATE TABLE IF NOT EXISTS mint_suspension (
    mint_id      VARCHAR(64)              NOT NULL,
    reason       TEXT,
    suspended_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_mint_suspension PRIMARY KEY (mint_id)
);
