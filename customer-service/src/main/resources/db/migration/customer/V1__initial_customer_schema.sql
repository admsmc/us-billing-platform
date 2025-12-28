-- Customer Service Schema
-- Manages customers, meters, billing periods, and meter reads for utility billing

-- Core customer record
CREATE TABLE customer (
    customer_id VARCHAR(36) PRIMARY KEY,
    utility_id VARCHAR(36) NOT NULL,
    account_number VARCHAR(50) NOT NULL,
    customer_name VARCHAR(200) NOT NULL,
    service_address TEXT NOT NULL,
    customer_class VARCHAR(50),  -- RESIDENTIAL, COMMERCIAL, INDUSTRIAL
    active BOOLEAN DEFAULT TRUE NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_utility_account UNIQUE(utility_id, account_number)
);

CREATE INDEX idx_customer_utility ON customer(utility_id);
CREATE INDEX idx_customer_active ON customer(active) WHERE active = TRUE;

-- Meter installation records
CREATE TABLE meter (
    meter_id VARCHAR(36) PRIMARY KEY,
    customer_id VARCHAR(36) NOT NULL REFERENCES customer(customer_id),
    utility_service_type VARCHAR(50) NOT NULL,  -- ELECTRIC, GAS, WATER, SEWER
    meter_number VARCHAR(50) NOT NULL,
    install_date DATE NOT NULL,
    removal_date DATE,
    active BOOLEAN DEFAULT TRUE NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_meter_number UNIQUE(meter_number)
);

CREATE INDEX idx_meter_customer ON meter(customer_id);
CREATE INDEX idx_meter_active ON meter(active, customer_id) WHERE active = TRUE;

-- Billing period (cycle window) for a customer
CREATE TABLE billing_period (
    period_id VARCHAR(36) PRIMARY KEY,
    customer_id VARCHAR(36) NOT NULL REFERENCES customer(customer_id),
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    status VARCHAR(20) NOT NULL,  -- OPEN, CLOSED, BILLED
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_period_dates CHECK (end_date > start_date)
);

CREATE INDEX idx_billing_period_customer ON billing_period(customer_id);
CREATE INDEX idx_billing_period_dates ON billing_period(start_date, end_date);

-- Meter readings (usage data points)
CREATE TABLE meter_read (
    read_id VARCHAR(36) PRIMARY KEY,
    meter_id VARCHAR(36) NOT NULL REFERENCES meter(meter_id),
    billing_period_id VARCHAR(36) REFERENCES billing_period(period_id),
    read_date DATE NOT NULL,
    reading_value DECIMAL(15,3) NOT NULL,
    reading_type VARCHAR(20) NOT NULL,  -- ACTUAL, ESTIMATED
    recorded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_reading_value CHECK (reading_value >= 0)
);

CREATE INDEX idx_meter_read_meter ON meter_read(meter_id, read_date DESC);
CREATE INDEX idx_meter_read_period ON meter_read(billing_period_id);

-- Comments for documentation
COMMENT ON TABLE customer IS 'Core customer master records for utility billing';
COMMENT ON TABLE meter IS 'Meter installations associated with customers';
COMMENT ON TABLE billing_period IS 'Billing cycle windows (typically monthly)';
COMMENT ON TABLE meter_read IS 'Usage data points from meter readings';

COMMENT ON COLUMN customer.customer_class IS 'RESIDENTIAL, COMMERCIAL, or INDUSTRIAL - determines applicable rate tariffs';
COMMENT ON COLUMN meter.utility_service_type IS 'ELECTRIC, GAS, WATER, or SEWER - the utility service this meter measures';
COMMENT ON COLUMN meter_read.reading_type IS 'ACTUAL (physical reading) or ESTIMATED (system-estimated when meter not accessible)';
