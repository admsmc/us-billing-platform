-- Service points and meters with bitemporal tracking

-- Service point table (bitemporal)
CREATE TABLE service_point_effective (
    service_point_id VARCHAR(255) NOT NULL,
    utility_id VARCHAR(255) NOT NULL,
    address_id VARCHAR(255) NOT NULL,
    service_type VARCHAR(50) NOT NULL, -- ELECTRIC, GAS, WATER, SEWER, WASTEWATER
    connection_status VARCHAR(50) NOT NULL, -- CONNECTED, DISCONNECTED, PENDING_CONNECTION, PENDING_DISCONNECTION
    rate_class VARCHAR(100),
    
    -- Valid time
    effective_from DATE NOT NULL,
    effective_to DATE NOT NULL DEFAULT '9999-12-31',
    
    -- System time
    system_from TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    system_to TIMESTAMP NOT NULL DEFAULT TIMESTAMP '9999-12-31 23:59:59',
    
    -- Audit
    created_by VARCHAR(255) NOT NULL,
    modified_by VARCHAR(255) NOT NULL,
    version_sequence INT NOT NULL DEFAULT 1,
    
    PRIMARY KEY (service_point_id, effective_from, system_from),
    CONSTRAINT chk_service_point_system_range CHECK (system_to > system_from),
    CONSTRAINT chk_service_point_effective_range CHECK (effective_to > effective_from)
);

CREATE INDEX idx_service_point_utility
    ON service_point_effective (utility_id, service_type, connection_status)
    WHERE system_from <= CURRENT_TIMESTAMP AND system_to > CURRENT_TIMESTAMP;

CREATE INDEX idx_service_point_address
    ON service_point_effective (address_id, effective_from, effective_to, system_from, system_to);

-- Meter table (bitemporal)
CREATE TABLE meter_effective (
    meter_id VARCHAR(255) NOT NULL,
    service_point_id VARCHAR(255) NOT NULL,
    meter_serial VARCHAR(200) NOT NULL,
    meter_type VARCHAR(50) NOT NULL, -- ELECTRIC_ANALOG, ELECTRIC_DIGITAL, ELECTRIC_SMART, GAS_ANALOG, etc.
    manufacturer VARCHAR(200),
    model VARCHAR(200),
    install_date DATE,
    last_read_date DATE,
    meter_status VARCHAR(50) NOT NULL, -- ACTIVE, INACTIVE, RETIRED, MAINTENANCE
    
    -- Valid time
    effective_from DATE NOT NULL,
    effective_to DATE NOT NULL DEFAULT '9999-12-31',
    
    -- System time
    system_from TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    system_to TIMESTAMP NOT NULL DEFAULT TIMESTAMP '9999-12-31 23:59:59',
    
    -- Audit
    created_by VARCHAR(255) NOT NULL,
    modified_by VARCHAR(255) NOT NULL,
    version_sequence INT NOT NULL DEFAULT 1,
    
    PRIMARY KEY (meter_id, effective_from, system_from),
    CONSTRAINT chk_meter_system_range CHECK (system_to > system_from),
    CONSTRAINT chk_meter_effective_range CHECK (effective_to > effective_from)
);

CREATE INDEX idx_meter_service_point
    ON meter_effective (service_point_id, meter_status)
    WHERE system_from <= CURRENT_TIMESTAMP AND system_to > CURRENT_TIMESTAMP;

CREATE INDEX idx_meter_serial
    ON meter_effective (meter_serial)
    WHERE system_from <= CURRENT_TIMESTAMP AND system_to > CURRENT_TIMESTAMP;

-- Service connection table (bitemporal) - links account to service point and meter
CREATE TABLE service_connection_effective (
    connection_id VARCHAR(255) NOT NULL,
    account_id VARCHAR(255) NOT NULL,
    service_point_id VARCHAR(255) NOT NULL,
    meter_id VARCHAR(255),
    connection_date DATE NOT NULL,
    disconnection_date DATE,
    connection_reason VARCHAR(500),
    
    -- Valid time
    effective_from DATE NOT NULL,
    effective_to DATE NOT NULL DEFAULT '9999-12-31',
    
    -- System time
    system_from TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    system_to TIMESTAMP NOT NULL DEFAULT TIMESTAMP '9999-12-31 23:59:59',
    
    -- Audit
    created_by VARCHAR(255) NOT NULL,
    modified_by VARCHAR(255) NOT NULL,
    
    PRIMARY KEY (connection_id, effective_from, system_from),
    CONSTRAINT chk_service_connection_system_range CHECK (system_to > system_from),
    CONSTRAINT chk_service_connection_effective_range CHECK (effective_to > effective_from)
);

CREATE INDEX idx_service_connection_account
    ON service_connection_effective (account_id, effective_from, effective_to, system_from, system_to);

CREATE INDEX idx_service_connection_service_point
    ON service_connection_effective (service_point_id, effective_from, effective_to, system_from, system_to);
