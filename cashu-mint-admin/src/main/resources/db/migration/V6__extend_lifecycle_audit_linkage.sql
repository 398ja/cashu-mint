ALTER TABLE mint_aggregate_snapshots ADD COLUMN audit_request_id UUID;
ALTER TABLE mint_aggregate_snapshots ADD COLUMN audit_correlation_id VARCHAR(255);

ALTER TABLE mint_lifecycle_history ADD COLUMN audit_request_id UUID;
ALTER TABLE mint_lifecycle_history ADD COLUMN audit_correlation_id VARCHAR(255);

ALTER TABLE audit_events ADD COLUMN request_id UUID;
ALTER TABLE audit_events ADD COLUMN correlation_id VARCHAR(255);

CREATE OR REPLACE VIEW v_mint_audit_log AS
SELECT a.mint_id,
       a.sequence,
       a.actor,
       a.action,
       a.event_timestamp,
       a.reason_codes,
       a.ticket_references,
       a.automation_automated,
       a.automation_system,
       a.automation_run_id,
       a.configuration_revision_id,
       a.notification_policy_email_enabled,
       a.notification_policy_webhook_enabled,
       a.notification_policy_throttle_interval_seconds,
       a.notification_policy_audit_actor,
       a.notification_policy_audit_action,
       a.notification_policy_audit_timestamp,
       a.request_id,
       a.correlation_id
FROM audit_events a;
