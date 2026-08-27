CREATE TABLE mint_lifecycle_approval_states (
    approval_id UUID PRIMARY KEY,
    mint_id UUID NOT NULL,
    target_state VARCHAR(32) NOT NULL,
    approval_status VARCHAR(32) NOT NULL,
    required_signoffs TEXT NOT NULL,
    granted_signoffs TEXT NOT NULL DEFAULT '[]',
    denied_signoffs TEXT NOT NULL DEFAULT '[]',
    expires_at TIMESTAMP WITH TIME ZONE,
    audit_actor VARCHAR(255) NOT NULL,
    audit_action VARCHAR(255) NOT NULL,
    audit_timestamp TIMESTAMP WITH TIME ZONE NOT NULL,
    audit_reason_codes TEXT,
    audit_ticket_references TEXT,
    audit_request_id UUID,
    audit_correlation_id VARCHAR(255)
);

CREATE INDEX idx_mint_lifecycle_approval_states_mint
    ON mint_lifecycle_approval_states (mint_id, target_state, approval_status);
