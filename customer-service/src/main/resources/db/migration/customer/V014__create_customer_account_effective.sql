-- Bitemporal customer account schema following SCD Type 2 pattern.
--
-- Valid time: effective_from/effective_to (when account attributes were true)
-- System time: system_from/system_to (when system knew about this version)
--
-- Current version query: system_from <= CURRENT_TIMESTAMP AND system_to > CURRENT_TIMESTAMP

-- Customer account master table (bitemporal)
CREATE TABLE customer_account_effective (
    account_id VARCHAR(255) NOT NULL,
    utility_id VARCHAR(255) NOT NULL,
    customer_id VARCHAR(255) NOT NULL,
    account_number VARCHAR(100) NOT NULL,
    account_type VARCHAR(50) NOT NULL, -- RESIDENTIAL, COMMERCIAL, INDUSTRIAL, AGRICULTURAL
    account_status VARCHAR(50) NOT NULL, -- PENDING_ACTIVATION, ACTIVE, SUSPENDED, CLOSED
    
    -- Account holder info
    holder_name VARCHAR(500) NOT NULL,
    holder_type VARCHAR(50) NOT NULL, -- INDIVIDUAL, BUSINESS, GOVERNMENT, NON_PROFIT
    identity_verified BOOLEAN NOT NULL DEFAULT FALSE,
    tax_id VARCHAR(100),
    business_name VARCHAR(500),
    
    -- Billing address
    billing_address_line1 VARCHAR(500) NOT NULL,
    billing_address_line2 VARCHAR(500),
    billing_city VARCHAR(200) NOT NULL,
    billing_state VARCHAR(50) NOT NULL,
    billing_postal_code VARCHAR(20) NOT NULL,
    billing_country VARCHAR(2) NOT NULL DEFAULT 'US',
    
    -- Valid time (business effective dates)
    effective_from DATE NOT NULL,
    effective_to DATE NOT NULL DEFAULT '9999-12-31',
    
    -- System time (audit/correction tracking)
    system_from TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    system_to TIMESTAMP NOT NULL DEFAULT TIMESTAMP '9999-12-31 23:59:59',
    
    -- Audit fields
    created_by VARCHAR(255) NOT NULL,
    modified_by VARCHAR(255) NOT NULL,
    version_sequence INT NOT NULL DEFAULT 1,
    change_reason VARCHAR(100),
    
    -- Bitemporal primary key
    PRIMARY KEY (account_id, effective_from, system_from),
    
    -- System time must be valid range
    CONSTRAINT chk_customer_account_system_range CHECK (system_to > system_from),
    CONSTRAINT chk_customer_account_effective_range CHECK (effective_to > effective_from)
);

-- Index for current version queries (most common query pattern)
-- Note: Removed temporal predicate from index - temporal filtering done in queries
CREATE INDEX idx_customer_account_current
    ON customer_account_effective (utility_id, customer_id, account_status, system_from, system_to);

-- Index for bitemporal queries
CREATE INDEX idx_customer_account_bitemporal
    ON customer_account_effective (account_id, effective_from, effective_to, system_from, system_to);

-- Index for account number lookups
-- Note: Removed temporal predicate from index - temporal filtering done in queries
CREATE INDEX idx_customer_account_number
    ON customer_account_effective (utility_id, account_number, system_from, system_to);

-- Service address table (bitemporal)
CREATE TABLE service_address_effective (
    address_id VARCHAR(255) NOT NULL,
    account_id VARCHAR(255) NOT NULL,
    address_line1 VARCHAR(500) NOT NULL,
    address_line2 VARCHAR(500),
    city VARCHAR(200) NOT NULL,
    state VARCHAR(50) NOT NULL,
    postal_code VARCHAR(20) NOT NULL,
    country VARCHAR(2) NOT NULL DEFAULT 'US',
    latitude DECIMAL(10, 7),
    longitude DECIMAL(10, 7),
    validated BOOLEAN NOT NULL DEFAULT FALSE,
    
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
    
    PRIMARY KEY (address_id, effective_from, system_from),
    CONSTRAINT chk_service_address_system_range CHECK (system_to > system_from),
    CONSTRAINT chk_service_address_effective_range CHECK (effective_to > effective_from)
);

CREATE INDEX idx_service_address_account
    ON service_address_effective (account_id, effective_from, effective_to, system_from, system_to);

-- Contact methods table (bitemporal)
CREATE TABLE contact_method_effective (
    contact_id VARCHAR(255) NOT NULL,
    account_id VARCHAR(255) NOT NULL,
    contact_type VARCHAR(50) NOT NULL, -- PHONE, EMAIL, SMS, POSTAL
    contact_value VARCHAR(500) NOT NULL,
    is_primary BOOLEAN NOT NULL DEFAULT FALSE,
    verified BOOLEAN NOT NULL DEFAULT FALSE,
    consent_for_marketing BOOLEAN NOT NULL DEFAULT FALSE,
    
    -- Valid time
    effective_from DATE NOT NULL,
    effective_to DATE NOT NULL DEFAULT '9999-12-31',
    
    -- System time
    system_from TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    system_to TIMESTAMP NOT NULL DEFAULT TIMESTAMP '9999-12-31 23:59:59',
    
    -- Audit
    created_by VARCHAR(255) NOT NULL,
    modified_by VARCHAR(255) NOT NULL,
    
    PRIMARY KEY (contact_id, effective_from, system_from),
    CONSTRAINT chk_contact_method_system_range CHECK (system_to > system_from),
    CONSTRAINT chk_contact_method_effective_range CHECK (effective_to > effective_from)
);

CREATE INDEX idx_contact_method_account
    ON contact_method_effective (account_id, effective_from, effective_to, system_from, system_to);
