-- Health monitoring and alerting are handled by cashu-mint-observability
-- (Prometheus, Grafana, SLO alert rules), not by the admin. The tables here
-- never had a producer: health snapshots were never written outside tests, and
-- alerts only ever held rows an operator typed in by hand.
--
-- configuration_revisions and notification_policies are deliberately kept —
-- they hold aggregate state (ConfigurationSet, NotificationPolicy) that the
-- mint lifecycle depends on, even though the governance use cases are gone.

DROP TABLE IF EXISTS admin_alert_escalations;
DROP TABLE IF EXISTS admin_alerts;
DROP TABLE IF EXISTS mint_health_snapshots;
