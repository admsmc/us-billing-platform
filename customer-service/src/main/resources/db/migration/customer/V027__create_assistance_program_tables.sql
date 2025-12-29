-- Assistance programs for low-income, senior, and medical customers

-- Assistance program catalog
CREATE TABLE assistance_program (
    program_id VARCHAR(255) PRIMARY KEY,
    utility_id VARCHAR(255) NOT NULL,
    program_code VARCHAR(50) NOT NULL UNIQUE,
    program_name VARCHAR(255) NOT NULL,
    program_type VARCHAR(50) NOT NULL, -- LIHEAP, SENIOR_DISCOUNT, MEDICAL_CERTIFICATE, CRISIS_ASSISTANCE, VETERAN_ASSISTANCE, DISABILITY_DISCOUNT
    benefit_type VARCHAR(50) NOT NULL, -- PERCENTAGE_DISCOUNT, FIXED_DISCOUNT, NO_DISCONNECT_PROTECTION, DEFERRED_PAYMENT
    benefit_value NUMERIC(10,2), -- Percentage or dollar amount
    eligibility_criteria TEXT NOT NULL,
    required_documents TEXT, -- Document types needed for verification
    requires_verification BOOLEAN NOT NULL DEFAULT TRUE,
    auto_renew BOOLEAN NOT NULL DEFAULT FALSE,
    renewal_period_months INT, -- How often renewal is required
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_program_utility ON assistance_program(utility_id);
CREATE INDEX idx_program_code ON assistance_program(program_code);
CREATE INDEX idx_program_active ON assistance_program(active);

-- Program enrollments
CREATE TABLE program_enrollment (
    enrollment_id VARCHAR(255) PRIMARY KEY,
    program_id VARCHAR(255) NOT NULL,
    customer_id VARCHAR(255) NOT NULL,
    account_id VARCHAR(255) NOT NULL,
    status VARCHAR(50) NOT NULL, -- PENDING, APPROVED, DENIED, EXPIRED, CANCELLED
    enrolled_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    effective_from DATE NOT NULL,
    effective_to DATE,
    verification_document_id VARCHAR(255),
    verification_notes TEXT,
    verified_by VARCHAR(255),
    verified_at TIMESTAMP,
    denial_reason TEXT,
    cancelled_reason TEXT,
    cancelled_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (program_id) REFERENCES assistance_program(program_id)
);

CREATE INDEX idx_enrollment_program ON program_enrollment(program_id);
CREATE INDEX idx_enrollment_customer ON program_enrollment(customer_id);
CREATE INDEX idx_enrollment_account ON program_enrollment(account_id);
CREATE INDEX idx_enrollment_status ON program_enrollment(status);
CREATE INDEX idx_enrollment_effective ON program_enrollment(effective_from, effective_to);

-- Benefit application history (tracks when benefits are applied to bills)
CREATE TABLE program_benefit_application (
    application_id VARCHAR(255) PRIMARY KEY,
    enrollment_id VARCHAR(255) NOT NULL,
    bill_id VARCHAR(255) NOT NULL,
    benefit_amount_cents BIGINT NOT NULL,
    applied_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    applied_by VARCHAR(255),
    notes TEXT,
    FOREIGN KEY (enrollment_id) REFERENCES program_enrollment(enrollment_id)
);

CREATE INDEX idx_benefit_enrollment ON program_benefit_application(enrollment_id);
CREATE INDEX idx_benefit_bill ON program_benefit_application(bill_id);
CREATE INDEX idx_benefit_applied_at ON program_benefit_application(applied_at);
