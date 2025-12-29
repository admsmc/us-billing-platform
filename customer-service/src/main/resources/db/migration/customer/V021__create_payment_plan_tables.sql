-- Payment Plan Tables
-- Enables customers to spread payment of large balances over time

-- Main payment plan table
CREATE TABLE payment_plan (
    plan_id VARCHAR(255) PRIMARY KEY,
    utility_id VARCHAR(255) NOT NULL,
    customer_id VARCHAR(255) NOT NULL,
    account_id VARCHAR(255) NOT NULL,
    
    -- Plan configuration
    plan_type VARCHAR(50) NOT NULL, -- STANDARD, HARDSHIP, BUDGET_BILLING
    status VARCHAR(50) NOT NULL, -- ACTIVE, COMPLETED, BROKEN, CANCELLED
    
    -- Financial details
    total_amount_cents BIGINT NOT NULL,
    down_payment_cents BIGINT NOT NULL DEFAULT 0,
    remaining_balance_cents BIGINT NOT NULL,
    installment_amount_cents BIGINT NOT NULL,
    installment_count INT NOT NULL,
    installments_paid INT NOT NULL DEFAULT 0,
    
    -- Schedule
    payment_frequency VARCHAR(50) NOT NULL, -- WEEKLY, BIWEEKLY, MONTHLY
    start_date DATE NOT NULL,
    first_payment_date DATE NOT NULL,
    final_payment_date DATE NOT NULL,
    
    -- Monitoring
    missed_payments INT NOT NULL DEFAULT 0,
    max_missed_payments INT NOT NULL DEFAULT 1,
    
    -- Audit
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(255) NOT NULL,
    cancelled_at TIMESTAMP,
    cancelled_reason VARCHAR(500)
);

CREATE INDEX idx_payment_plan_customer
    ON payment_plan (customer_id, status);

CREATE INDEX idx_payment_plan_account
    ON payment_plan (account_id, status);

CREATE INDEX idx_payment_plan_status
    ON payment_plan (utility_id, status, first_payment_date);

-- Payment plan installments
CREATE TABLE payment_plan_installment (
    installment_id VARCHAR(255) PRIMARY KEY,
    plan_id VARCHAR(255) NOT NULL,
    installment_number INT NOT NULL,
    due_date DATE NOT NULL,
    amount_cents BIGINT NOT NULL,
    paid_amount_cents BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(50) NOT NULL, -- PENDING, PAID, PARTIAL, MISSED, WAIVED
    paid_at TIMESTAMP,
    FOREIGN KEY (plan_id) REFERENCES payment_plan(plan_id),
    UNIQUE (plan_id, installment_number)
);

CREATE INDEX idx_payment_plan_installment_plan
    ON payment_plan_installment (plan_id, installment_number);

CREATE INDEX idx_payment_plan_installment_due
    ON payment_plan_installment (plan_id, due_date)
    WHERE status = 'PENDING';

-- Links payments to installments
CREATE TABLE payment_plan_payment (
    id VARCHAR(255) PRIMARY KEY,
    plan_id VARCHAR(255) NOT NULL,
    installment_id VARCHAR(255) NOT NULL,
    payment_id VARCHAR(255) NOT NULL, -- References payment in payments-service
    amount_cents BIGINT NOT NULL,
    applied_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (plan_id) REFERENCES payment_plan(plan_id),
    FOREIGN KEY (installment_id) REFERENCES payment_plan_installment(installment_id)
);

CREATE INDEX idx_payment_plan_payment_plan
    ON payment_plan_payment (plan_id, applied_at DESC);

CREATE INDEX idx_payment_plan_payment_installment
    ON payment_plan_payment (installment_id);

-- Comments
COMMENT ON TABLE payment_plan IS 
'Payment plans allow customers to spread large balance payments over time with scheduled installments.';

COMMENT ON COLUMN payment_plan.plan_type IS 
'STANDARD: normal payment plan, HARDSHIP: reduced payments for financial hardship, BUDGET_BILLING: even monthly payments';

COMMENT ON COLUMN payment_plan.status IS 
'ACTIVE: plan in progress, COMPLETED: all installments paid, BROKEN: missed too many payments, CANCELLED: manually cancelled';

COMMENT ON COLUMN payment_plan.max_missed_payments IS 
'Number of consecutive missed payments before plan is marked BROKEN (default: 1)';
