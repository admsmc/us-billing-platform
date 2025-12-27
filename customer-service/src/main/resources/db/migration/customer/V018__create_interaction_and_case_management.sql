-- Customer interaction logging (append-only)
CREATE TABLE customer_interaction (
    interaction_id VARCHAR(255) PRIMARY KEY,
    utility_id VARCHAR(255) NOT NULL,
    account_id VARCHAR(255),
    customer_id VARCHAR(255),
    
    -- Interaction classification
    interaction_type VARCHAR(50) NOT NULL, -- INQUIRY, SERVICE_REQUEST, COMPLAINT, PAYMENT, etc.
    interaction_channel VARCHAR(50) NOT NULL, -- PHONE, EMAIL, SMS, CHAT, WEB_PORTAL, etc.
    interaction_reason VARCHAR(50) NOT NULL, -- BILLING_INQUIRY, HIGH_BILL, METER_ISSUE, etc.
    direction VARCHAR(20) NOT NULL, -- INBOUND, OUTBOUND
    
    -- Interaction details
    initiated_by VARCHAR(255), -- CSR ID or system
    summary VARCHAR(1000) NOT NULL,
    details TEXT,
    outcome VARCHAR(50), -- RESOLVED, ESCALATED, CASE_CREATED, etc.
    follow_up_required BOOLEAN NOT NULL DEFAULT FALSE,
    follow_up_date DATE,
    duration_seconds INT,
    sentiment VARCHAR(20), -- POSITIVE, NEUTRAL, NEGATIVE, VERY_NEGATIVE
    
    -- Metadata
    timestamp TIMESTAMP NOT NULL,
    tags TEXT[], -- Array of tags for categorization
    related_case_id VARCHAR(255),
    
    -- Audit
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_interaction_utility_timestamp
    ON customer_interaction (utility_id, timestamp DESC);

CREATE INDEX idx_interaction_account
    ON customer_interaction (account_id, timestamp DESC) WHERE account_id IS NOT NULL;

CREATE INDEX idx_interaction_customer
    ON customer_interaction (customer_id, timestamp DESC) WHERE customer_id IS NOT NULL;

CREATE INDEX idx_interaction_type_reason
    ON customer_interaction (utility_id, interaction_type, interaction_reason, timestamp DESC);

CREATE INDEX idx_interaction_follow_up
    ON customer_interaction (utility_id, follow_up_date)
    WHERE follow_up_required = TRUE AND follow_up_date IS NOT NULL;

-- Case management table
CREATE TABLE case_record (
    case_id VARCHAR(255) PRIMARY KEY,
    case_number VARCHAR(100) NOT NULL UNIQUE, -- User-friendly case number
    utility_id VARCHAR(255) NOT NULL,
    account_id VARCHAR(255),
    customer_id VARCHAR(255),
    
    -- Case classification
    case_type VARCHAR(50) NOT NULL, -- SERVICE_REQUEST, COMPLAINT, DISPUTE, etc.
    case_category VARCHAR(50) NOT NULL, -- BILLING, PAYMENT, METER, SERVICE_CONNECTION, etc.
    status VARCHAR(50) NOT NULL, -- OPEN, IN_PROGRESS, RESOLVED, CLOSED, ESCALATED, etc.
    priority VARCHAR(20) NOT NULL, -- LOW, MEDIUM, HIGH, CRITICAL
    severity VARCHAR(20), -- MINOR, MODERATE, MAJOR, CRITICAL
    
    -- Case content
    title VARCHAR(500) NOT NULL,
    description TEXT NOT NULL,
    resolution_notes TEXT,
    root_cause TEXT,
    preventative_action TEXT,
    
    -- Assignment
    assigned_to VARCHAR(255),
    assigned_team VARCHAR(255),
    opened_by VARCHAR(255) NOT NULL,
    closed_by VARCHAR(255),
    
    -- Timeline
    estimated_resolution_date DATE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    closed_at TIMESTAMP,
    
    -- Metadata
    tags TEXT[],
    related_case_ids TEXT[]
);

CREATE INDEX idx_case_utility_status
    ON case_record (utility_id, status, priority, created_at DESC);

CREATE INDEX idx_case_account
    ON case_record (account_id, status, created_at DESC) WHERE account_id IS NOT NULL;

CREATE INDEX idx_case_customer
    ON case_record (customer_id, status, created_at DESC) WHERE customer_id IS NOT NULL;

CREATE INDEX idx_case_assigned_to
    ON case_record (assigned_to, status, priority) WHERE assigned_to IS NOT NULL;

CREATE INDEX idx_case_assigned_team
    ON case_record (assigned_team, status, priority) WHERE assigned_team IS NOT NULL;

CREATE INDEX idx_case_number
    ON case_record (utility_id, case_number);

CREATE INDEX idx_case_type_category
    ON case_record (utility_id, case_type, case_category, status);

-- Case status history (append-only)
CREATE TABLE case_status_history (
    history_id VARCHAR(255) PRIMARY KEY,
    case_id VARCHAR(255) NOT NULL,
    from_status VARCHAR(50),
    to_status VARCHAR(50) NOT NULL,
    changed_by VARCHAR(255) NOT NULL,
    changed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reason VARCHAR(500),
    notes TEXT
);

CREATE INDEX idx_case_status_history_case
    ON case_status_history (case_id, changed_at DESC);

-- Case notes (append-only)
CREATE TABLE case_note (
    note_id VARCHAR(255) PRIMARY KEY,
    case_id VARCHAR(255) NOT NULL,
    note_type VARCHAR(50) NOT NULL, -- COMMENT, RESOLUTION_ATTEMPT, CUSTOMER_CONTACT, etc.
    content TEXT NOT NULL,
    is_internal BOOLEAN NOT NULL DEFAULT FALSE,
    created_by VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    attachment_ids TEXT[]
);

CREATE INDEX idx_case_note_case
    ON case_note (case_id, created_at DESC);

CREATE INDEX idx_case_note_created_by
    ON case_note (created_by, created_at DESC);

-- Case assignment history (append-only)
CREATE TABLE case_assignment_history (
    assignment_id VARCHAR(255) PRIMARY KEY,
    case_id VARCHAR(255) NOT NULL,
    from_assignee VARCHAR(255),
    to_assignee VARCHAR(255),
    from_team VARCHAR(255),
    to_team VARCHAR(255),
    assigned_by VARCHAR(255) NOT NULL,
    assigned_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reason VARCHAR(500)
);

CREATE INDEX idx_case_assignment_history_case
    ON case_assignment_history (case_id, assigned_at DESC);

-- Notification preferences (bitemporal)
CREATE TABLE notification_preference_effective (
    preference_id VARCHAR(255) NOT NULL,
    customer_id VARCHAR(255) NOT NULL,
    utility_id VARCHAR(255) NOT NULL,
    
    -- Channel preferences
    email_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    sms_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    phone_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    mail_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    push_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    
    -- Content preferences
    billing_alerts BOOLEAN NOT NULL DEFAULT TRUE,
    payment_reminders BOOLEAN NOT NULL DEFAULT TRUE,
    outage_notifications BOOLEAN NOT NULL DEFAULT TRUE,
    usage_alerts BOOLEAN NOT NULL DEFAULT FALSE,
    marketing BOOLEAN NOT NULL DEFAULT FALSE,
    promotional BOOLEAN NOT NULL DEFAULT FALSE,
    
    -- Bitemporal
    effective_from DATE NOT NULL,
    effective_to DATE NOT NULL DEFAULT '9999-12-31',
    system_from TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    system_to TIMESTAMP NOT NULL DEFAULT TIMESTAMP '9999-12-31 23:59:59',
    
    -- Audit
    created_by VARCHAR(255) NOT NULL,
    modified_by VARCHAR(255) NOT NULL,
    
    PRIMARY KEY (preference_id, effective_from, system_from),
    CONSTRAINT chk_notification_preference_system_range CHECK (system_to > system_from),
    CONSTRAINT chk_notification_preference_effective_range CHECK (effective_to > effective_from)
);

CREATE INDEX idx_notification_preference_customer
    ON notification_preference_effective (customer_id, utility_id)
    WHERE system_from <= CURRENT_TIMESTAMP AND system_to > CURRENT_TIMESTAMP;

-- Communication log (append-only)
CREATE TABLE communication_log (
    log_id VARCHAR(255) PRIMARY KEY,
    utility_id VARCHAR(255) NOT NULL,
    account_id VARCHAR(255),
    customer_id VARCHAR(255),
    
    -- Communication details
    channel VARCHAR(50) NOT NULL, -- EMAIL, SMS, PHONE, MAIL, PUSH
    communication_type VARCHAR(50) NOT NULL, -- BILL, NOTICE, REMINDER, ALERT, etc.
    subject VARCHAR(500),
    content_summary VARCHAR(1000),
    recipient VARCHAR(500) NOT NULL,
    
    -- Delivery tracking
    status VARCHAR(50) NOT NULL, -- SENT, DELIVERED, FAILED, BOUNCED, OPENED, CLICKED
    sent_at TIMESTAMP NOT NULL,
    delivered_at TIMESTAMP,
    opened_at TIMESTAMP,
    failed_reason VARCHAR(500),
    
    -- Context
    related_document_id VARCHAR(255),
    related_case_id VARCHAR(255),
    template_id VARCHAR(255),
    
    -- Metadata
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_communication_log_utility
    ON communication_log (utility_id, sent_at DESC);

CREATE INDEX idx_communication_log_account
    ON communication_log (account_id, sent_at DESC) WHERE account_id IS NOT NULL;

CREATE INDEX idx_communication_log_customer
    ON communication_log (customer_id, sent_at DESC) WHERE customer_id IS NOT NULL;

CREATE INDEX idx_communication_log_status
    ON communication_log (utility_id, status, sent_at DESC);
