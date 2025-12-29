-- Billing period table with bitemporal tracking (SCD2)
-- Supports complete history tracking for billing periods

DROP TABLE IF EXISTS billing_period_effective CASCADE;

CREATE TABLE billing_period_effective (
    period_id VARCHAR(255) NOT NULL,
    account_id VARCHAR(255) NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    due_date DATE NOT NULL,
    status VARCHAR(50) NOT NULL, -- OPEN, CLOSED, POSTED
    
    -- Bitemporal columns - Effective time
    effective_from DATE NOT NULL,
    effective_to DATE NOT NULL DEFAULT '9999-12-31',
    
    -- Bitemporal columns - System time
    system_from TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    system_to TIMESTAMP NOT NULL DEFAULT TIMESTAMP '9999-12-31 23:59:59',
    
    -- Audit fields
    created_by VARCHAR(255) NOT NULL,
    modified_by VARCHAR(255) NOT NULL,
    version_sequence INT NOT NULL DEFAULT 1,
    
    -- Bitemporal primary key
    PRIMARY KEY (period_id, effective_from, system_from),
    
    -- Constraints
    CONSTRAINT chk_billing_period_system_range CHECK (system_to > system_from),
    CONSTRAINT chk_billing_period_effective_range CHECK (effective_to > effective_from),
    CONSTRAINT chk_billing_period_dates CHECK (end_date >= start_date)
);

-- Index for current version queries (most common query pattern)
CREATE INDEX IF NOT EXISTS idx_billing_period_account
    ON billing_period_effective (account_id, system_from, system_to, effective_from, effective_to);

-- Index for date range queries
CREATE INDEX IF NOT EXISTS idx_billing_period_dates
    ON billing_period_effective (start_date, end_date, system_from, system_to);

-- Index for bitemporal queries
CREATE INDEX IF NOT EXISTS idx_billing_period_bitemporal
    ON billing_period_effective (period_id, effective_from, effective_to, system_from, system_to);

COMMENT ON TABLE billing_period_effective IS 'Bitemporal billing periods using SCD2 pattern for complete history tracking';
COMMENT ON COLUMN billing_period_effective.effective_from IS 'When this version became effective (business time)';
COMMENT ON COLUMN billing_period_effective.effective_to IS 'When this version ceased to be effective (business time)';
COMMENT ON COLUMN billing_period_effective.system_from IS 'When this version was recorded in the system (system time)';
COMMENT ON COLUMN billing_period_effective.system_to IS 'When this version was superseded in the system (system time)';
