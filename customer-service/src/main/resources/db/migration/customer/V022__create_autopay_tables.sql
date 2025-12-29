-- Auto-Pay Schema

-- Auto-pay enrollment
CREATE TABLE autopay_enrollment (
    enrollment_id VARCHAR(255) PRIMARY KEY,
    utility_id VARCHAR(255) NOT NULL,
    customer_id VARCHAR(255) NOT NULL,
    account_id VARCHAR(255) NOT NULL,
    payment_method_id VARCHAR(255) NOT NULL,
    status VARCHAR(50) NOT NULL, -- ACTIVE, SUSPENDED, CANCELLED
    payment_timing VARCHAR(50) NOT NULL, -- ON_DUE_DATE, FIXED_DAY
    fixed_day_of_month INT, -- 1-28 if FIXED_DAY
    amount_type VARCHAR(50) NOT NULL, -- FULL_BALANCE, MINIMUM_DUE, FIXED_AMOUNT
    fixed_amount_cents BIGINT, -- if FIXED_AMOUNT
    enrolled_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    enrolled_by VARCHAR(255) NOT NULL,
    cancelled_at TIMESTAMP,
    cancelled_reason VARCHAR(500),
    consecutive_failures INT NOT NULL DEFAULT 0
);

CREATE INDEX idx_autopay_enrollment_customer ON autopay_enrollment(customer_id);
CREATE INDEX idx_autopay_enrollment_account ON autopay_enrollment(account_id);
CREATE INDEX idx_autopay_enrollment_status ON autopay_enrollment(status);

-- Auto-pay execution history (append-only audit trail)
CREATE TABLE autopay_execution (
    execution_id VARCHAR(255) PRIMARY KEY,
    enrollment_id VARCHAR(255) NOT NULL,
    bill_id VARCHAR(255),
    scheduled_date DATE NOT NULL,
    executed_at TIMESTAMP,
    amount_cents BIGINT NOT NULL,
    status VARCHAR(50) NOT NULL, -- SCHEDULED, SUCCESS, FAILED, SKIPPED
    failure_reason VARCHAR(500),
    payment_id VARCHAR(255), -- if successful
    retry_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (enrollment_id) REFERENCES autopay_enrollment(enrollment_id)
);

CREATE INDEX idx_autopay_execution_enrollment ON autopay_execution(enrollment_id);
CREATE INDEX idx_autopay_execution_scheduled ON autopay_execution(scheduled_date);
CREATE INDEX idx_autopay_execution_status ON autopay_execution(status);
CREATE INDEX idx_autopay_execution_bill ON autopay_execution(bill_id);
