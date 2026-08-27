CREATE TABLE operational_controls (
    control_id UUID PRIMARY KEY,
    mint_id UUID NOT NULL,
    operator_id UUID NOT NULL,
    control_type VARCHAR(32) NOT NULL,
    status VARCHAR(64) NOT NULL,
    reason TEXT,
    duration_minutes INTEGER,
    scheduled_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_operational_controls_mint_type_status
    ON operational_controls (mint_id, control_type, status, scheduled_at);
