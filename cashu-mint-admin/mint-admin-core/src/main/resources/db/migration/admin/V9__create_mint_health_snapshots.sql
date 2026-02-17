CREATE TABLE mint_health_snapshots (
    mint_id UUID PRIMARY KEY,
    health_status VARCHAR(32) NOT NULL,
    lifecycle_state VARCHAR(32),
    checked_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_mint_health_snapshots_status ON mint_health_snapshots (health_status);
