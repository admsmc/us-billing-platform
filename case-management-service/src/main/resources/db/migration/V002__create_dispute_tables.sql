-- Dispute Management Schema

-- Billing dispute
CREATE TABLE billing_dispute (
    dispute_id VARCHAR(255) PRIMARY KEY,
    utility_id VARCHAR(255) NOT NULL,
    customer_id VARCHAR(255) NOT NULL,
    account_id VARCHAR(255) NOT NULL,
    bill_id VARCHAR(255),
    dispute_type VARCHAR(50) NOT NULL, -- HIGH_BILL, ESTIMATED_BILL, METER_ACCURACY, SERVICE_QUALITY, CHARGE_ERROR
    dispute_reason TEXT NOT NULL,
    disputed_amount_cents BIGINT,
    status VARCHAR(50) NOT NULL, -- SUBMITTED, INVESTIGATING, RESOLVED, CLOSED, ESCALATED
    priority VARCHAR(20) NOT NULL DEFAULT 'MEDIUM',
    submitted_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at TIMESTAMP,
    case_id VARCHAR(255), -- Link to case_record if escalated
    created_by VARCHAR(255)
);

CREATE INDEX idx_billing_dispute_customer ON billing_dispute(customer_id);
CREATE INDEX idx_billing_dispute_account ON billing_dispute(account_id);
CREATE INDEX idx_billing_dispute_status ON billing_dispute(status);
CREATE INDEX idx_billing_dispute_bill ON billing_dispute(bill_id);

-- Dispute investigation
CREATE TABLE dispute_investigation (
    investigation_id VARCHAR(255) PRIMARY KEY,
    dispute_id VARCHAR(255) NOT NULL,
    assigned_to VARCHAR(255) NOT NULL,
    investigation_notes TEXT,
    meter_test_requested BOOLEAN NOT NULL DEFAULT FALSE,
    meter_test_result VARCHAR(100),
    field_visit_required BOOLEAN NOT NULL DEFAULT FALSE,
    field_visit_completed_at TIMESTAMP,
    findings TEXT,
    recommendation VARCHAR(100), -- APPROVE_ADJUSTMENT, DENY, PARTIAL_ADJUSTMENT, ESCALATE
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (dispute_id) REFERENCES billing_dispute(dispute_id)
);

CREATE INDEX idx_dispute_investigation_dispute ON dispute_investigation(dispute_id);
CREATE INDEX idx_dispute_investigation_assigned ON dispute_investigation(assigned_to);

-- Dispute resolution
CREATE TABLE dispute_resolution (
    resolution_id VARCHAR(255) PRIMARY KEY,
    dispute_id VARCHAR(255) NOT NULL,
    resolution_type VARCHAR(50) NOT NULL, -- ADJUSTMENT_APPROVED, DENIED, PARTIAL_ADJUSTMENT, ESCALATED_TO_COMMISSION
    adjustment_amount_cents BIGINT,
    adjustment_applied BOOLEAN NOT NULL DEFAULT FALSE,
    resolution_notes TEXT NOT NULL,
    customer_notified_at TIMESTAMP,
    resolved_by VARCHAR(255) NOT NULL,
    resolved_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (dispute_id) REFERENCES billing_dispute(dispute_id)
);

CREATE INDEX idx_dispute_resolution_dispute ON dispute_resolution(dispute_id);
CREATE INDEX idx_dispute_resolution_type ON dispute_resolution(resolution_type);
