CREATE TABLE mint_aggregate_snapshots (
    mint_id UUID PRIMARY KEY,
    lifecycle_state VARCHAR(32) NOT NULL,
    configuration_revision_id BIGINT NOT NULL,
    version_tag VARCHAR(128),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE mint_lifecycle_history (
    event_id UUID PRIMARY KEY,
    mint_id UUID NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    previous_state VARCHAR(32),
    current_state VARCHAR(32) NOT NULL,
    configuration_revision_id BIGINT NOT NULL,
    version_tag VARCHAR(128),
    audit_actor VARCHAR(255) NOT NULL,
    audit_action VARCHAR(255) NOT NULL,
    audit_timestamp TIMESTAMP WITH TIME ZONE NOT NULL,
    audit_reason_codes TEXT,
    audit_ticket_references TEXT,
    audit_automation_automated BOOLEAN,
    audit_automation_system VARCHAR(255),
    audit_automation_run_id VARCHAR(255)
);

CREATE INDEX idx_mint_lifecycle_history_mint
    ON mint_lifecycle_history (mint_id, audit_timestamp);
