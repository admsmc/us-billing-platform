-- Rate Service Schema
-- Manages utility rate tariffs, pricing schedules, and rate catalog

-- Core rate tariff record
CREATE TABLE rate_tariff (
    tariff_id VARCHAR(36) PRIMARY KEY,
    utility_id VARCHAR(36) NOT NULL,
    tariff_name VARCHAR(200) NOT NULL,
    tariff_code VARCHAR(50) NOT NULL,
    rate_structure VARCHAR(50) NOT NULL,  -- FLAT, TIERED, TOU, DEMAND
    utility_service_type VARCHAR(50) NOT NULL,  -- ELECTRIC, GAS, WATER, etc.
    customer_class VARCHAR(50),  -- RESIDENTIAL, COMMERCIAL, INDUSTRIAL
    effective_date DATE NOT NULL,
    expiry_date DATE,
    active BOOLEAN DEFAULT TRUE NOT NULL,
    readiness_to_serve_cents INT NOT NULL,  -- Fixed monthly charge in cents
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_tariff_code UNIQUE(utility_id, tariff_code, effective_date),
    CONSTRAINT chk_effective_dates CHECK (expiry_date IS NULL OR expiry_date > effective_date)
);

CREATE INDEX idx_rate_tariff_utility ON rate_tariff(utility_id);
CREATE INDEX idx_rate_tariff_service ON rate_tariff(utility_service_type);
CREATE INDEX idx_rate_tariff_class ON rate_tariff(customer_class);
CREATE INDEX idx_rate_tariff_dates ON rate_tariff(effective_date, expiry_date);
CREATE INDEX idx_rate_tariff_active ON rate_tariff(active, utility_id) WHERE active = TRUE;

-- Rate components - pricing elements for a tariff
CREATE TABLE rate_component (
    component_id VARCHAR(36) PRIMARY KEY,
    tariff_id VARCHAR(36) NOT NULL REFERENCES rate_tariff(tariff_id) ON DELETE CASCADE,
    charge_type VARCHAR(50) NOT NULL,  -- ENERGY, DEMAND, TIER, TOU_PEAK, TOU_OFF_PEAK, TOU_SHOULDER
    rate_value_cents INT NOT NULL,  -- Rate per unit in cents (e.g., 12 cents/kWh = 12)
    threshold DECIMAL(15,3),  -- For tiered rates: max usage for this tier (NULL for top tier)
    tou_period VARCHAR(50),  -- For TOU: PEAK, OFF_PEAK, SHOULDER
    season VARCHAR(20),  -- SUMMER, WINTER, YEAR_ROUND
    component_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_rate_component_tariff ON rate_component(tariff_id, component_order);
CREATE INDEX idx_rate_component_type ON rate_component(charge_type);

-- Time-of-use schedules - define when TOU periods apply
CREATE TABLE tou_schedule (
    schedule_id VARCHAR(36) PRIMARY KEY,
    tariff_id VARCHAR(36) NOT NULL REFERENCES rate_tariff(tariff_id) ON DELETE CASCADE,
    schedule_name VARCHAR(100) NOT NULL,
    season VARCHAR(20),  -- SUMMER, WINTER, YEAR_ROUND
    tou_period VARCHAR(50) NOT NULL,  -- PEAK, OFF_PEAK, SHOULDER
    start_hour INT NOT NULL,  -- Hour of day (0-23)
    end_hour INT NOT NULL,  -- Hour of day (0-23), exclusive
    day_of_week_mask INT NOT NULL,  -- Bit mask: Sun=1, Mon=2, Tue=4, Wed=8, Thu=16, Fri=32, Sat=64
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_hour_range CHECK (start_hour >= 0 AND start_hour < 24 AND end_hour > 0 AND end_hour <= 24)
);

CREATE INDEX idx_tou_schedule_tariff ON tou_schedule(tariff_id);
CREATE INDEX idx_tou_schedule_season ON tou_schedule(season);

-- Regulatory surcharges associated with tariffs
CREATE TABLE tariff_regulatory_charge (
    charge_id VARCHAR(36) PRIMARY KEY,
    tariff_id VARCHAR(36) NOT NULL REFERENCES rate_tariff(tariff_id) ON DELETE CASCADE,
    charge_code VARCHAR(50) NOT NULL,
    charge_description VARCHAR(200) NOT NULL,
    calculation_type VARCHAR(50) NOT NULL,  -- FIXED, PERCENTAGE_OF_ENERGY, PER_UNIT, PERCENTAGE_OF_TOTAL
    rate_value_cents INT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_tariff_reg_charge_tariff ON tariff_regulatory_charge(tariff_id);

-- Comments for documentation
COMMENT ON TABLE rate_tariff IS 'Master tariff records defining rate structures for utility services';
COMMENT ON TABLE rate_component IS 'Pricing components that make up a tariff (tiers, TOU rates, demand charges)';
COMMENT ON TABLE tou_schedule IS 'Time-of-use schedules defining when peak/off-peak rates apply';
COMMENT ON TABLE tariff_regulatory_charge IS 'Regulatory surcharges and riders associated with tariffs';

COMMENT ON COLUMN rate_tariff.rate_structure IS 'FLAT (simple $/unit), TIERED (progressive blocks), TOU (time-of-use), DEMAND (capacity-based)';
COMMENT ON COLUMN rate_tariff.readiness_to_serve_cents IS 'Fixed monthly customer charge in cents (e.g., $15.00 = 1500)';
COMMENT ON COLUMN rate_component.rate_value_cents IS 'Rate per unit in cents; 12 cents/kWh stored as 12, $0.50/CCF stored as 50';
COMMENT ON COLUMN tou_schedule.day_of_week_mask IS 'Bit mask for days: Sunday=1, Monday=2, ..., Saturday=64. E.g., weekdays=126 (Mon-Fri)';
