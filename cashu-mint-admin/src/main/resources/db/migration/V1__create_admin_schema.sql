CREATE TABLE mints (
    mint_id UUID PRIMARY KEY,
    lifecycle_state VARCHAR(32) NOT NULL,
    current_configuration_revision BIGINT NOT NULL,
    last_actor VARCHAR(255) NOT NULL,
    last_action VARCHAR(255) NOT NULL,
    last_timestamp TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE configuration_revisions (
    mint_id UUID NOT NULL,
    revision_id BIGINT NOT NULL,
    parameters TEXT NOT NULL,
    audit_actor VARCHAR(255) NOT NULL,
    audit_action VARCHAR(255) NOT NULL,
    audit_timestamp TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (mint_id, revision_id)
);

CREATE TABLE operator_accounts (
    mint_id UUID PRIMARY KEY,
    operator_id UUID NOT NULL,
    display_name VARCHAR(255) NOT NULL,
    roles TEXT NOT NULL,
    audit_actor VARCHAR(255) NOT NULL,
    audit_action VARCHAR(255) NOT NULL,
    audit_timestamp TIMESTAMPTZ NOT NULL
);

CREATE TABLE notification_policies (
    mint_id UUID PRIMARY KEY,
    email_enabled BOOLEAN NOT NULL,
    webhook_enabled BOOLEAN NOT NULL,
    throttle_interval_seconds BIGINT NOT NULL,
    audit_actor VARCHAR(255) NOT NULL,
    audit_action VARCHAR(255) NOT NULL,
    audit_timestamp TIMESTAMPTZ NOT NULL
);

CREATE TABLE audit_events (
    mint_id UUID NOT NULL,
    sequence BIGINT NOT NULL,
    actor VARCHAR(255) NOT NULL,
    action VARCHAR(255) NOT NULL,
    event_timestamp TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (mint_id, sequence)
);

CREATE INDEX idx_audit_events_mint_timestamp ON audit_events (mint_id, event_timestamp);

CREATE TABLE admin_outbox (
    event_id UUID PRIMARY KEY,
    aggregate_id UUID NOT NULL,
    aggregate_type VARCHAR(128) NOT NULL,
    event_type VARCHAR(255) NOT NULL,
    payload TEXT NOT NULL,
    attributes TEXT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    available_at TIMESTAMPTZ NOT NULL,
    last_attempt_at TIMESTAMPTZ,
    dispatched_at TIMESTAMPTZ,
    delivery_attempts INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX idx_admin_outbox_pending ON admin_outbox (available_at)
    WHERE dispatched_at IS NULL;

CREATE VIEW v_mint_overview AS
SELECT m.mint_id,
       m.lifecycle_state,
       m.current_configuration_revision AS current_revision,
       m.last_actor,
       m.last_action,
       m.last_timestamp,
       oa.display_name AS operator_display_name,
       oa.roles AS operator_roles,
       np.email_enabled,
       np.webhook_enabled,
       np.throttle_interval_seconds
FROM mints m
JOIN operator_accounts oa ON oa.mint_id = m.mint_id
JOIN notification_policies np ON np.mint_id = m.mint_id;

CREATE VIEW v_configuration_history AS
SELECT c.mint_id,
       c.revision_id,
       c.parameters,
       c.audit_actor,
       c.audit_action,
       c.audit_timestamp
FROM configuration_revisions c;

CREATE VIEW v_mint_audit_log AS
SELECT a.mint_id,
       a.sequence,
       a.actor,
       a.action,
       a.event_timestamp
FROM audit_events a;
