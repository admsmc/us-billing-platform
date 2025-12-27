-- Customer entity and account-customer relationship tables

-- Customer table (bitemporal) - represents individuals or organizations
CREATE TABLE customer_effective (
    customer_id VARCHAR(255) NOT NULL,
    utility_id VARCHAR(255) NOT NULL,
    customer_type VARCHAR(50) NOT NULL, -- INDIVIDUAL, BUSINESS, GOVERNMENT, NON_PROFIT
    
    -- Profile information
    first_name VARCHAR(200),
    last_name VARCHAR(200),
    middle_name VARCHAR(100),
    business_name VARCHAR(500),
    tax_id VARCHAR(100),
    identity_verified BOOLEAN NOT NULL DEFAULT FALSE,
    identity_verification_date DATE,
    date_of_birth DATE,
    preferred_language VARCHAR(10) NOT NULL DEFAULT 'en',
    preferred_name VARCHAR(200),
    
    -- Bitemporal fields
    effective_from DATE NOT NULL,
    effective_to DATE NOT NULL DEFAULT '9999-12-31',
    system_from TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    system_to TIMESTAMP NOT NULL DEFAULT TIMESTAMP '9999-12-31 23:59:59',
    
    -- Audit
    created_by VARCHAR(255) NOT NULL,
    modified_by VARCHAR(255) NOT NULL,
    version_sequence INT NOT NULL DEFAULT 1,
    
    PRIMARY KEY (customer_id, effective_from, system_from),
    CONSTRAINT chk_customer_system_range CHECK (system_to > system_from),
    CONSTRAINT chk_customer_effective_range CHECK (effective_to > effective_from)
);

CREATE INDEX idx_customer_utility
    ON customer_effective (utility_id, customer_type)
    WHERE system_from <= CURRENT_TIMESTAMP AND system_to > CURRENT_TIMESTAMP;

CREATE INDEX idx_customer_name
    ON customer_effective (utility_id, last_name, first_name)
    WHERE system_from <= CURRENT_TIMESTAMP AND system_to > CURRENT_TIMESTAMP;

CREATE INDEX idx_customer_business_name
    ON customer_effective (utility_id, business_name)
    WHERE business_name IS NOT NULL 
      AND system_from <= CURRENT_TIMESTAMP 
      AND system_to > CURRENT_TIMESTAMP;

-- Account-Customer relationship table (bitemporal)
-- Links customers to accounts with specific roles
CREATE TABLE account_customer_role_effective (
    relationship_id VARCHAR(255) NOT NULL,
    account_id VARCHAR(255) NOT NULL,
    customer_id VARCHAR(255) NOT NULL,
    role_type VARCHAR(50) NOT NULL, -- ACCOUNT_HOLDER, BILL_PAYER, PROPERTY_OWNER, LANDLORD, TENANT, etc.
    is_primary BOOLEAN NOT NULL DEFAULT FALSE,
    authorization_level VARCHAR(50) NOT NULL, -- FULL, LIMITED, READ_ONLY, EMERGENCY_ONLY
    notes TEXT,
    
    -- Bitemporal fields
    effective_from DATE NOT NULL,
    effective_to DATE NOT NULL DEFAULT '9999-12-31',
    system_from TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    system_to TIMESTAMP NOT NULL DEFAULT TIMESTAMP '9999-12-31 23:59:59',
    
    -- Audit
    created_by VARCHAR(255) NOT NULL,
    modified_by VARCHAR(255) NOT NULL,
    
    PRIMARY KEY (relationship_id, effective_from, system_from),
    CONSTRAINT chk_account_customer_role_system_range CHECK (system_to > system_from),
    CONSTRAINT chk_account_customer_role_effective_range CHECK (effective_to > effective_from)
);

CREATE INDEX idx_account_customer_role_account
    ON account_customer_role_effective (account_id, role_type)
    WHERE system_from <= CURRENT_TIMESTAMP AND system_to > CURRENT_TIMESTAMP;

CREATE INDEX idx_account_customer_role_customer
    ON account_customer_role_effective (customer_id, role_type)
    WHERE system_from <= CURRENT_TIMESTAMP AND system_to > CURRENT_TIMESTAMP;

-- Property table (bitemporal) - represents physical properties
CREATE TABLE property_effective (
    property_id VARCHAR(255) NOT NULL,
    utility_id VARCHAR(255) NOT NULL,
    
    -- Address (denormalized for quick lookup)
    address_line1 VARCHAR(500) NOT NULL,
    address_line2 VARCHAR(500),
    city VARCHAR(200) NOT NULL,
    state VARCHAR(50) NOT NULL,
    postal_code VARCHAR(20) NOT NULL,
    country VARCHAR(2) NOT NULL DEFAULT 'US',
    latitude DECIMAL(10, 7),
    longitude DECIMAL(10, 7),
    
    -- Property details
    property_type VARCHAR(50) NOT NULL, -- SINGLE_FAMILY, MULTI_FAMILY, COMMERCIAL_OFFICE, etc.
    property_status VARCHAR(50) NOT NULL, -- ACTIVE, VACANT, UNDER_CONSTRUCTION, etc.
    property_class VARCHAR(100), -- Zoning classification
    square_footage INT,
    number_of_units INT,
    year_built INT,
    notes TEXT,
    
    -- Bitemporal fields
    effective_from DATE NOT NULL,
    effective_to DATE NOT NULL DEFAULT '9999-12-31',
    system_from TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    system_to TIMESTAMP NOT NULL DEFAULT TIMESTAMP '9999-12-31 23:59:59',
    
    -- Audit
    created_by VARCHAR(255) NOT NULL,
    modified_by VARCHAR(255) NOT NULL,
    
    PRIMARY KEY (property_id, effective_from, system_from),
    CONSTRAINT chk_property_system_range CHECK (system_to > system_from),
    CONSTRAINT chk_property_effective_range CHECK (effective_to > effective_from)
);

CREATE INDEX idx_property_utility
    ON property_effective (utility_id, property_type, property_status)
    WHERE system_from <= CURRENT_TIMESTAMP AND system_to > CURRENT_TIMESTAMP;

CREATE INDEX idx_property_address
    ON property_effective (utility_id, postal_code, address_line1)
    WHERE system_from <= CURRENT_TIMESTAMP AND system_to > CURRENT_TIMESTAMP;

-- Update customer_account_effective to link to property (optional)
ALTER TABLE customer_account_effective
    ADD COLUMN IF NOT EXISTS property_id VARCHAR(255);

CREATE INDEX idx_customer_account_property
    ON customer_account_effective (property_id)
    WHERE property_id IS NOT NULL
      AND system_from <= CURRENT_TIMESTAMP 
      AND system_to > CURRENT_TIMESTAMP;
