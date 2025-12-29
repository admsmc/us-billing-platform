-- Service Request Schema

-- Service request
CREATE TABLE service_request (
    request_id VARCHAR(255) PRIMARY KEY,
    utility_id VARCHAR(255) NOT NULL,
    customer_id VARCHAR(255) NOT NULL,
    account_id VARCHAR(255) NOT NULL,
    request_type VARCHAR(50) NOT NULL, -- START_SERVICE, STOP_SERVICE, TRANSFER_SERVICE, MOVE_SERVICE, METER_TEST, RECONNECT
    service_type VARCHAR(50) NOT NULL, -- ELECTRIC, GAS, WATER
    service_address TEXT NOT NULL,
    requested_date DATE,
    status VARCHAR(50) NOT NULL, -- SUBMITTED, SCHEDULED, IN_PROGRESS, COMPLETED, CANCELLED
    priority VARCHAR(20) NOT NULL DEFAULT 'NORMAL', -- NORMAL, URGENT, EMERGENCY
    work_order_id VARCHAR(255),
    notes TEXT,
    submitted_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP,
    case_id VARCHAR(255) -- Link to case if needed
);

CREATE INDEX idx_service_request_customer ON service_request(customer_id);
CREATE INDEX idx_service_request_account ON service_request(account_id);
CREATE INDEX idx_service_request_status ON service_request(status);
CREATE INDEX idx_service_request_type ON service_request(request_type);

-- Service request appointment
CREATE TABLE service_request_appointment (
    appointment_id VARCHAR(255) PRIMARY KEY,
    request_id VARCHAR(255) NOT NULL,
    scheduled_date DATE NOT NULL,
    time_window VARCHAR(50) NOT NULL, -- AM, PM, MORNING, AFTERNOON, SPECIFIC_TIME
    start_time TIME,
    end_time TIME,
    technician_id VARCHAR(255),
    status VARCHAR(50) NOT NULL, -- SCHEDULED, CONFIRMED, IN_PROGRESS, COMPLETED, CANCELLED, RESCHEDULED
    customer_notified BOOLEAN NOT NULL DEFAULT FALSE,
    notes TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (request_id) REFERENCES service_request(request_id)
);

CREATE INDEX idx_service_request_appointment_request ON service_request_appointment(request_id);
CREATE INDEX idx_service_request_appointment_date ON service_request_appointment(scheduled_date);
CREATE INDEX idx_service_request_appointment_technician ON service_request_appointment(technician_id);
CREATE INDEX idx_service_request_appointment_status ON service_request_appointment(status);
