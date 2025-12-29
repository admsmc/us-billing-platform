-- Technician management and availability scheduling

-- Technician table
CREATE TABLE technician (
    technician_id VARCHAR(255) PRIMARY KEY,
    utility_id VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    email VARCHAR(255),
    phone VARCHAR(50),
    service_types TEXT[] NOT NULL, -- Services they can handle (e.g., ELECTRIC, GAS, WATER)
    skill_level VARCHAR(50) NOT NULL DEFAULT 'STANDARD', -- STANDARD, ADVANCED, MASTER
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_technician_utility ON technician(utility_id);
CREATE INDEX idx_technician_active ON technician(active);

-- Technician availability table
CREATE TABLE technician_availability (
    availability_id VARCHAR(255) PRIMARY KEY,
    technician_id VARCHAR(255) NOT NULL,
    date DATE NOT NULL,
    time_window VARCHAR(50) NOT NULL, -- AM, PM, ALL_DAY, CUSTOM
    start_time TIME, -- For CUSTOM time window
    end_time TIME,   -- For CUSTOM time window
    available BOOLEAN NOT NULL DEFAULT TRUE,
    max_appointments INT NOT NULL DEFAULT 4,
    booked_appointments INT NOT NULL DEFAULT 0,
    notes TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (technician_id) REFERENCES technician(technician_id)
);

CREATE INDEX idx_availability_technician ON technician_availability(technician_id);
CREATE INDEX idx_availability_date ON technician_availability(date);
CREATE INDEX idx_availability_technician_date ON technician_availability(technician_id, date);

-- Update service_request_appointment to link to technician (from Sprint 11)
-- Note: service_request_appointment already has technician_id column from Sprint 11
-- This migration ensures the FK constraint is properly set if not already done

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.table_constraints 
        WHERE constraint_name = 'fk_appointment_technician' 
        AND table_name = 'service_request_appointment'
    ) THEN
        ALTER TABLE service_request_appointment 
        ADD CONSTRAINT fk_appointment_technician 
        FOREIGN KEY (technician_id) REFERENCES technician(technician_id);
    END IF;
END $$;
