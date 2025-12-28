-- Billing Orchestrator Schema
-- Manages bill persistence and lifecycle

-- Core bill record
CREATE TABLE bill (
    bill_id VARCHAR(36) PRIMARY KEY,
    customer_id VARCHAR(36) NOT NULL,
    utility_id VARCHAR(36) NOT NULL,
    billing_period_id VARCHAR(36) NOT NULL,
    bill_number VARCHAR(50) UNIQUE,
    status VARCHAR(20) NOT NULL,  -- DRAFT, COMPUTING, FINALIZED, ISSUED, VOIDED
    total_amount_cents BIGINT NOT NULL,
    due_date DATE NOT NULL,
    bill_date DATE NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_bill_customer ON bill(customer_id);
CREATE INDEX idx_bill_utility ON bill(utility_id);
CREATE INDEX idx_bill_status ON bill(status);
CREATE INDEX idx_bill_dates ON bill(bill_date, due_date);

-- Bill line items
CREATE TABLE bill_line (
    line_id VARCHAR(36) PRIMARY KEY,
    bill_id VARCHAR(36) NOT NULL REFERENCES bill(bill_id) ON DELETE CASCADE,
    service_type VARCHAR(50) NOT NULL,
    charge_type VARCHAR(50) NOT NULL,
    description VARCHAR(500),
    usage_amount DECIMAL(15,3),
    rate_value_cents BIGINT,
    line_amount_cents BIGINT NOT NULL,
    line_order INT NOT NULL
);

CREATE INDEX idx_bill_line_bill ON bill_line(bill_id, line_order);

-- Bill audit events
CREATE TABLE bill_event (
    event_id VARCHAR(36) PRIMARY KEY,
    bill_id VARCHAR(36) NOT NULL REFERENCES bill(bill_id) ON DELETE CASCADE,
    event_type VARCHAR(50) NOT NULL,
    event_data TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_bill_event_bill ON bill_event(bill_id, created_at DESC);

COMMENT ON TABLE bill IS 'Core bill records with lifecycle management';
COMMENT ON TABLE bill_line IS 'Individual line item charges on bills';
COMMENT ON TABLE bill_event IS 'Audit trail of bill lifecycle events';
COMMENT ON COLUMN bill.status IS 'DRAFT (new), COMPUTING (worker processing), FINALIZED (computed), ISSUED (sent to customer), VOIDED (cancelled)';
