-- Outbox pattern for reliable event publishing (transactional outbox)
CREATE TABLE outbox_event (
    outbox_id VARCHAR(255) PRIMARY KEY,
    status VARCHAR(20) NOT NULL, -- PENDING, SENDING, SENT
    
    -- Event identification
    event_id VARCHAR(255), -- Optional idempotency key
    topic VARCHAR(255) NOT NULL,
    event_key VARCHAR(500) NOT NULL,
    event_type VARCHAR(255) NOT NULL,
    aggregate_id VARCHAR(255),
    
    -- Payload
    payload_json TEXT NOT NULL,
    
    -- Retry/lock management
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP,
    last_error TEXT,
    locked_by VARCHAR(255),
    locked_at TIMESTAMP,
    
    -- Timestamps
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMP
);

-- Unique constraint on event_id for idempotency
CREATE UNIQUE INDEX idx_outbox_event_id ON outbox_event (event_id) WHERE event_id IS NOT NULL;

-- Index for claiming pending events
CREATE INDEX idx_outbox_pending
    ON outbox_event (status, next_attempt_at, locked_at)
    WHERE status = 'PENDING';

-- Index for monitoring and cleanup
CREATE INDEX idx_outbox_created ON outbox_event (created_at, status);

-- Customer audit event table (comprehensive audit trail)
CREATE TABLE customer_audit_event (
    audit_id BIGSERIAL PRIMARY KEY,
    event_id VARCHAR(255) NOT NULL UNIQUE,
    
    -- What changed
    aggregate_type VARCHAR(100) NOT NULL, -- CustomerAccount, ServicePoint, Meter, ServiceConnection
    aggregate_id VARCHAR(255) NOT NULL,
    event_type VARCHAR(100) NOT NULL, -- Created, Updated, Suspended, Closed, etc.
    
    -- Who and when
    changed_by VARCHAR(255) NOT NULL,
    changed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    change_reason VARCHAR(100),
    
    -- What changed (before/after for updates)
    before_json TEXT,
    after_json TEXT,
    
    -- Context
    utility_id VARCHAR(255) NOT NULL,
    request_id VARCHAR(255),
    correlation_id VARCHAR(255),
    user_agent VARCHAR(500),
    source_ip VARCHAR(50)
);

-- Index for querying audit trail by aggregate
CREATE INDEX idx_customer_audit_aggregate
    ON customer_audit_event (aggregate_type, aggregate_id, changed_at DESC);

-- Index for querying by utility
CREATE INDEX idx_customer_audit_utility
    ON customer_audit_event (utility_id, changed_at DESC);

-- Index for correlation tracking
CREATE INDEX idx_customer_audit_correlation
    ON customer_audit_event (correlation_id) WHERE correlation_id IS NOT NULL;
