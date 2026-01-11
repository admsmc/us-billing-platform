-- Document Management Schema
-- Supports document upload, versioning, and lifecycle management

CREATE TABLE document (
    document_id VARCHAR(255) PRIMARY KEY,
    utility_id VARCHAR(255) NOT NULL,
    customer_id VARCHAR(255),
    account_id VARCHAR(255),
    document_type VARCHAR(50) NOT NULL, -- BILL, PROOF_OF_OWNERSHIP, LEASE, ID, MEDICAL_CERTIFICATE, VERIFICATION, OTHER
    document_name VARCHAR(500) NOT NULL,
    content_type VARCHAR(100) NOT NULL, -- MIME type (application/pdf, image/jpeg, etc.)
    storage_key VARCHAR(500) NOT NULL, -- S3 key or file path
    file_size_bytes BIGINT NOT NULL,
    uploaded_by VARCHAR(255),
    uploaded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    retention_until DATE, -- For retention policy and automatic cleanup
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at TIMESTAMP,
    deleted_by VARCHAR(255)
);

CREATE INDEX idx_document_utility ON document(utility_id);
CREATE INDEX idx_document_customer ON document(customer_id);
CREATE INDEX idx_document_account ON document(account_id);
CREATE INDEX idx_document_type ON document(document_type);
CREATE INDEX idx_document_uploaded_at ON document(uploaded_at);
CREATE INDEX idx_document_retention ON document(retention_until) WHERE retention_until IS NOT NULL;
CREATE INDEX idx_document_active ON document(deleted) WHERE deleted = FALSE;

-- Document versioning table for tracking changes and history
CREATE TABLE document_version (
    version_id VARCHAR(255) PRIMARY KEY,
    document_id VARCHAR(255) NOT NULL,
    version_number INT NOT NULL,
    storage_key VARCHAR(500) NOT NULL, -- S3 key or file path for this version
    file_size_bytes BIGINT NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    uploaded_by VARCHAR(255),
    uploaded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version_notes TEXT,
    FOREIGN KEY (document_id) REFERENCES document(document_id)
);

CREATE INDEX idx_document_version_document ON document_version(document_id);
CREATE INDEX idx_document_version_number ON document_version(document_id, version_number);
CREATE INDEX idx_document_version_uploaded_at ON document_version(uploaded_at);

COMMENT ON TABLE document IS 'Stores document metadata and references to stored files';
COMMENT ON TABLE document_version IS 'Tracks document version history for audit and rollback';

COMMENT ON COLUMN document.document_type IS 'Type of document: BILL, PROOF_OF_OWNERSHIP, LEASE, ID, MEDICAL_CERTIFICATE, VERIFICATION, OTHER';
COMMENT ON COLUMN document.storage_key IS 'Storage location identifier (S3 key, file path, etc.)';
COMMENT ON COLUMN document.retention_until IS 'Date when document can be automatically purged (null = keep indefinitely)';
COMMENT ON COLUMN document.deleted IS 'Soft delete flag - document marked for deletion but not physically removed';
