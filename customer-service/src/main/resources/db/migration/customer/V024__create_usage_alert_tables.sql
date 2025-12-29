-- Usage Alert Schema

-- Usage alert configuration
CREATE TABLE usage_alert_config (
    config_id VARCHAR(255) PRIMARY KEY,
    customer_id VARCHAR(255) NOT NULL,
    account_id VARCHAR(255) NOT NULL,
    alert_type VARCHAR(50) NOT NULL, -- HIGH_USAGE, BUDGET_EXCEEDED, UNUSUAL_PATTERN
    threshold_type VARCHAR(50) NOT NULL, -- PERCENTAGE, ABSOLUTE_KWH, DOLLAR_AMOUNT
    threshold_value NUMERIC(10,2) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_usage_alert_config_customer ON usage_alert_config(customer_id);
CREATE INDEX idx_usage_alert_config_account ON usage_alert_config(account_id);
CREATE INDEX idx_usage_alert_config_enabled ON usage_alert_config(enabled);

-- Usage alert history (append-only)
CREATE TABLE usage_alert_history (
    alert_id VARCHAR(255) PRIMARY KEY,
    config_id VARCHAR(255) NOT NULL,
    triggered_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    alert_message TEXT NOT NULL,
    actual_value NUMERIC(10,2) NOT NULL,
    threshold_value NUMERIC(10,2) NOT NULL,
    notification_sent BOOLEAN NOT NULL DEFAULT FALSE,
    FOREIGN KEY (config_id) REFERENCES usage_alert_config(config_id)
);

CREATE INDEX idx_usage_alert_history_config ON usage_alert_history(config_id);
CREATE INDEX idx_usage_alert_history_triggered ON usage_alert_history(triggered_at);
CREATE INDEX idx_usage_alert_history_notification ON usage_alert_history(notification_sent);
