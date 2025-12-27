-- CSR (Customer Service Representative) identity table
CREATE TABLE csr_identity (
    csr_id VARCHAR(255) PRIMARY KEY,
    utility_id VARCHAR(255) NOT NULL,
    employee_id VARCHAR(255) NOT NULL,
    first_name VARCHAR(255) NOT NULL,
    last_name VARCHAR(255) NOT NULL,
    email VARCHAR(255) NOT NULL,
    team_id VARCHAR(255),
    role VARCHAR(50) NOT NULL, -- CSR, SENIOR_CSR, SUPERVISOR, MANAGER, SPECIALIST
    is_active BOOLEAN NOT NULL DEFAULT true,
    hire_date DATE NOT NULL,
    termination_date DATE,
    skill_set TEXT[], -- Array of case categories (BILLING, TECHNICAL, etc.)
    max_concurrent_cases INTEGER NOT NULL DEFAULT 20,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_csr_identity_utility ON csr_identity(utility_id);
CREATE INDEX idx_csr_identity_team ON csr_identity(team_id);
CREATE INDEX idx_csr_identity_active ON csr_identity(is_active) WHERE is_active = true;

-- Team organizational unit table
CREATE TABLE team (
    team_id VARCHAR(255) PRIMARY KEY,
    utility_id VARCHAR(255) NOT NULL,
    team_name VARCHAR(255) NOT NULL,
    team_type VARCHAR(50) NOT NULL, -- TIER_1, TIER_2, TIER_3, BILLING, TECHNICAL, etc.
    supervisor_id VARCHAR(255),
    is_active BOOLEAN NOT NULL DEFAULT true,
    specialization TEXT[], -- Array of case categories
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    FOREIGN KEY (supervisor_id) REFERENCES csr_identity(csr_id) ON DELETE SET NULL
);

CREATE INDEX idx_team_utility ON team(utility_id);
CREATE INDEX idx_team_supervisor ON team(supervisor_id);
CREATE INDEX idx_team_active ON team(is_active) WHERE is_active = true;

-- SLA configuration per case type and priority
CREATE TABLE sla_configuration (
    sla_id VARCHAR(255) PRIMARY KEY,
    utility_id VARCHAR(255) NOT NULL,
    case_type VARCHAR(50) NOT NULL,
    case_priority VARCHAR(50) NOT NULL,
    response_time_minutes INTEGER NOT NULL,
    resolution_time_minutes INTEGER NOT NULL,
    escalation_threshold_minutes INTEGER NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (utility_id, case_type, case_priority)
);

CREATE INDEX idx_sla_config_utility ON sla_configuration(utility_id);
CREATE INDEX idx_sla_config_active ON sla_configuration(is_active) WHERE is_active = true;

-- Add foreign key to case_record for assigned_to CSR
ALTER TABLE case_record
ADD CONSTRAINT fk_case_assigned_to_csr
FOREIGN KEY (assigned_to) REFERENCES csr_identity(csr_id) ON DELETE SET NULL;

-- Add foreign key to case_record for assigned_team
ALTER TABLE case_record
ADD CONSTRAINT fk_case_assigned_to_team
FOREIGN KEY (assigned_team) REFERENCES team(team_id) ON DELETE SET NULL;

-- Seed default SLA configurations for util-001
INSERT INTO sla_configuration (sla_id, utility_id, case_type, case_priority, response_time_minutes, resolution_time_minutes, escalation_threshold_minutes, is_active)
VALUES
    ('sla-critical-service', 'util-001', 'SERVICE_REQUEST', 'CRITICAL', 15, 240, 120, true),
    ('sla-high-service', 'util-001', 'SERVICE_REQUEST', 'HIGH', 60, 720, 360, true),
    ('sla-medium-service', 'util-001', 'SERVICE_REQUEST', 'MEDIUM', 240, 2880, 1440, true),
    ('sla-low-service', 'util-001', 'SERVICE_REQUEST', 'LOW', 1440, 10080, 5040, true),
    ('sla-critical-complaint', 'util-001', 'COMPLAINT', 'CRITICAL', 15, 240, 120, true),
    ('sla-high-complaint', 'util-001', 'COMPLAINT', 'HIGH', 60, 720, 360, true),
    ('sla-medium-complaint', 'util-001', 'COMPLAINT', 'MEDIUM', 240, 2880, 1440, true),
    ('sla-low-complaint', 'util-001', 'COMPLAINT', 'LOW', 1440, 10080, 5040, true);

-- Seed sample CSR identities and teams for util-001
INSERT INTO team (team_id, utility_id, team_name, team_type, supervisor_id, is_active, specialization)
VALUES
    ('team-tier1', 'util-001', 'Tier 1 Support', 'TIER_1', NULL, true, ARRAY['BILLING', 'ACCOUNT_MANAGEMENT']),
    ('team-tier2', 'util-001', 'Tier 2 Technical', 'TIER_2', NULL, true, ARRAY['METER', 'SERVICE_CONNECTION', 'OUTAGE']),
    ('team-billing', 'util-001', 'Billing Specialists', 'BILLING', NULL, true, ARRAY['BILLING', 'PAYMENT']);

INSERT INTO csr_identity (csr_id, utility_id, employee_id, first_name, last_name, email, team_id, role, is_active, hire_date, skill_set, max_concurrent_cases)
VALUES
    ('csr-001', 'util-001', 'EMP-001', 'Alice', 'Johnson', 'alice.johnson@utility.com', 'team-tier1', 'CSR', true, '2023-01-15', ARRAY['BILLING', 'ACCOUNT_MANAGEMENT'], 20),
    ('csr-002', 'util-001', 'EMP-002', 'Bob', 'Smith', 'bob.smith@utility.com', 'team-tier1', 'SENIOR_CSR', true, '2022-06-10', ARRAY['BILLING', 'ACCOUNT_MANAGEMENT', 'PAYMENT'], 25),
    ('csr-003', 'util-001', 'EMP-003', 'Carol', 'Williams', 'carol.williams@utility.com', 'team-tier2', 'SPECIALIST', true, '2021-03-20', ARRAY['METER', 'SERVICE_CONNECTION'], 15),
    ('csr-004', 'util-001', 'EMP-004', 'David', 'Brown', 'david.brown@utility.com', 'team-billing', 'CSR', true, '2023-08-05', ARRAY['BILLING', 'PAYMENT'], 20),
    ('csr-sup-001', 'util-001', 'EMP-SUP-001', 'Emma', 'Davis', 'emma.davis@utility.com', 'team-tier1', 'SUPERVISOR', true, '2020-01-10', ARRAY['BILLING', 'ACCOUNT_MANAGEMENT', 'CUSTOMER_SERVICE'], 10);

-- Update team supervisors
UPDATE team SET supervisor_id = 'csr-sup-001' WHERE team_id = 'team-tier1';
