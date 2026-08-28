ALTER TABLE mints ADD COLUMN last_reason_codes TEXT;
ALTER TABLE mints ADD COLUMN last_ticket_references TEXT;
ALTER TABLE mints ADD COLUMN last_automation_automated BOOLEAN;
ALTER TABLE mints ADD COLUMN last_automation_system VARCHAR(255);
ALTER TABLE mints ADD COLUMN last_automation_run_id VARCHAR(255);

ALTER TABLE configuration_revisions ADD COLUMN audit_reason_codes TEXT;
ALTER TABLE configuration_revisions ADD COLUMN audit_ticket_references TEXT;
ALTER TABLE configuration_revisions ADD COLUMN audit_automation_automated BOOLEAN;
ALTER TABLE configuration_revisions ADD COLUMN audit_automation_system VARCHAR(255);
ALTER TABLE configuration_revisions ADD COLUMN audit_automation_run_id VARCHAR(255);

ALTER TABLE operator_accounts ADD COLUMN audit_reason_codes TEXT;
ALTER TABLE operator_accounts ADD COLUMN audit_ticket_references TEXT;
ALTER TABLE operator_accounts ADD COLUMN audit_automation_automated BOOLEAN;
ALTER TABLE operator_accounts ADD COLUMN audit_automation_system VARCHAR(255);
ALTER TABLE operator_accounts ADD COLUMN audit_automation_run_id VARCHAR(255);

ALTER TABLE notification_policies ADD COLUMN audit_reason_codes TEXT;
ALTER TABLE notification_policies ADD COLUMN audit_ticket_references TEXT;
ALTER TABLE notification_policies ADD COLUMN audit_automation_automated BOOLEAN;
ALTER TABLE notification_policies ADD COLUMN audit_automation_system VARCHAR(255);
ALTER TABLE notification_policies ADD COLUMN audit_automation_run_id VARCHAR(255);

ALTER TABLE audit_events ADD COLUMN reason_codes TEXT;
ALTER TABLE audit_events ADD COLUMN ticket_references TEXT;
ALTER TABLE audit_events ADD COLUMN automation_automated BOOLEAN;
ALTER TABLE audit_events ADD COLUMN automation_system VARCHAR(255);
ALTER TABLE audit_events ADD COLUMN automation_run_id VARCHAR(255);

DROP VIEW IF EXISTS v_mint_audit_log;

CREATE VIEW v_mint_audit_log AS
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
       a.notification_policy_audit_timestamp
FROM audit_events a;
