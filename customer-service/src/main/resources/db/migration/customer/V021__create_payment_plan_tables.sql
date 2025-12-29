-- Payment Plan Tables
-- Enables customers to spread payment of large balances over time

-- Main payment plan table (SCD2 bitemporal)
CREATE TABLE payment_plan (
    plan_id VARCHAR(255) NOT NULL,
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
    
    -- Bitemporal SCD2 fields
    system_from TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    system_to TIMESTAMP NOT NULL DEFAULT TIMESTAMP '9999-12-31 23:59:59',
    modified_by VARCHAR(255) NOT NULL,
    
    -- Audit
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(255) NOT NULL,
    cancelled_at TIMESTAMP,
    cancelled_reason VARCHAR(500),
    
    -- Composite primary key for SCD2
    PRIMARY KEY (plan_id, system_from),
    
    -- Constraint: system_to > system_from
    CONSTRAINT chk_payment_plan_system_range CHECK (system_to > system_from)
);

-- Index for current versions only
CREATE INDEX idx_payment_plan_current
    ON payment_plan (plan_id, system_to)
    WHERE system_to = TIMESTAMP '9999-12-31 23:59:59';

CREATE INDEX idx_payment_plan_customer
    ON payment_plan (customer_id, status)
    WHERE system_to = TIMESTAMP '9999-12-31 23:59:59';

CREATE INDEX idx_payment_plan_account
    ON payment_plan (account_id, status)
    WHERE system_to = TIMESTAMP '9999-12-31 23:59:59';

CREATE INDEX idx_payment_plan_status
    ON payment_plan (utility_id, status, first_payment_date)
    WHERE system_to = TIMESTAMP '9999-12-31 23:59:59';

-- Index for system-time queries (audit/history)
CREATE INDEX idx_payment_plan_system_time
    ON payment_plan (plan_id, system_from DESC, system_to DESC);

-- Payment plan installments (SCD2 bitemporal)
CREATE TABLE payment_plan_installment (
    installment_id VARCHAR(255) NOT NULL,
    plan_id VARCHAR(255) NOT NULL,
    installment_number INT NOT NULL,
    due_date DATE NOT NULL,
    amount_cents BIGINT NOT NULL,
    paid_amount_cents BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(50) NOT NULL, -- PENDING, PAID, PARTIAL, MISSED, WAIVED
    paid_at TIMESTAMP,
    
    -- Bitemporal SCD2 fields
    system_from TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    system_to TIMESTAMP NOT NULL DEFAULT TIMESTAMP '9999-12-31 23:59:59',
    modified_by VARCHAR(255) NOT NULL,
    
    -- Composite primary key for SCD2
    PRIMARY KEY (installment_id, system_from),
    
    -- Constraint: system_to > system_from
    CONSTRAINT chk_installment_system_range CHECK (system_to > system_from),
    
    -- Unique constraint for current version only
    UNIQUE (plan_id, installment_number, system_from)
);

-- Index for current versions only
CREATE INDEX idx_payment_plan_installment_current
    ON payment_plan_installment (installment_id, system_to)
    WHERE system_to = TIMESTAMP '9999-12-31 23:59:59';

CREATE INDEX idx_payment_plan_installment_plan
    ON payment_plan_installment (plan_id, installment_number)
    WHERE system_to = TIMESTAMP '9999-12-31 23:59:59';

CREATE INDEX idx_payment_plan_installment_due
    ON payment_plan_installment (plan_id, due_date)
    WHERE status = 'PENDING' AND system_to = TIMESTAMP '9999-12-31 23:59:59';

-- Index for system-time queries
CREATE INDEX idx_installment_system_time
    ON payment_plan_installment (installment_id, system_from DESC, system_to DESC);

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
'Bitemporal SCD2: Payment plans allow customers to spread large balance payments over time. All changes create new rows.';

COMMENT ON COLUMN payment_plan.plan_type IS 
'STANDARD: normal payment plan, HARDSHIP: reduced payments for financial hardship, BUDGET_BILLING: even monthly payments';

COMMENT ON COLUMN payment_plan.status IS 
'ACTIVE: plan in progress, COMPLETED: all installments paid, BROKEN: missed too many payments, CANCELLED: manually cancelled';

COMMENT ON COLUMN payment_plan.max_missed_payments IS 
'Number of consecutive missed payments before plan is marked BROKEN (default: 1)';

COMMENT ON COLUMN payment_plan.system_from IS 
'System time start: when this version became current in the database';

COMMENT ON COLUMN payment_plan.system_to IS 
'System time end: when this version was superseded. 9999-12-31 for current version.';

COMMENT ON TABLE payment_plan_installment IS 
'Bitemporal SCD2: Installments track payment progress. All changes create new rows.';

COMMENT ON TABLE payment_plan_payment IS 
'Append-only: Links payments to installments. Never updated.';

COMMENT ON COLUMN payment_plan_installment.system_from IS 
'System time start: when this version became current';

COMMENT ON COLUMN payment_plan_installment.system_to IS 
'System time end: when superseded. 9999-12-31 for current version.';
