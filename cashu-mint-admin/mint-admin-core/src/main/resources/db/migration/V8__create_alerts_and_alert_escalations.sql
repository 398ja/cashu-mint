CREATE TABLE admin_alerts (
    alert_id VARCHAR(255) PRIMARY KEY,
    mint_id VARCHAR(255) NOT NULL,
    severity VARCHAR(32) NOT NULL,
    summary VARCHAR(1024) NOT NULL,
    labels TEXT NOT NULL DEFAULT '{}',
    acknowledged BOOLEAN NOT NULL DEFAULT FALSE,
    silenced BOOLEAN NOT NULL DEFAULT FALSE,
    silence_minutes INTEGER,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE admin_alert_escalations (
    alert_id VARCHAR(255) NOT NULL,
    policy_id VARCHAR(255) NOT NULL,
    escalated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (alert_id, policy_id),
    CONSTRAINT fk_admin_alert_escalations_alert
        FOREIGN KEY (alert_id) REFERENCES admin_alerts (alert_id) ON DELETE CASCADE
);

CREATE INDEX idx_admin_alerts_mint_id ON admin_alerts (mint_id);
