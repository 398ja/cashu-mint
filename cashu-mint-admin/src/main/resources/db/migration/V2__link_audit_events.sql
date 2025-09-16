ALTER TABLE audit_events ADD COLUMN configuration_revision_id BIGINT;
ALTER TABLE audit_events ADD COLUMN notification_policy_email_enabled BOOLEAN;
ALTER TABLE audit_events ADD COLUMN notification_policy_webhook_enabled BOOLEAN;
ALTER TABLE audit_events ADD COLUMN notification_policy_throttle_interval_seconds BIGINT;
ALTER TABLE audit_events ADD COLUMN notification_policy_audit_actor VARCHAR(255);
ALTER TABLE audit_events ADD COLUMN notification_policy_audit_action VARCHAR(255);
ALTER TABLE audit_events ADD COLUMN notification_policy_audit_timestamp TIMESTAMP WITH TIME ZONE;

CREATE OR REPLACE VIEW v_mint_audit_log AS
SELECT a.mint_id,
       a.sequence,
       a.actor,
       a.action,
       a.event_timestamp,
       a.configuration_revision_id,
       a.notification_policy_email_enabled,
       a.notification_policy_webhook_enabled,
       a.notification_policy_throttle_interval_seconds,
       a.notification_policy_audit_actor,
       a.notification_policy_audit_action,
       a.notification_policy_audit_timestamp
FROM audit_events a;
