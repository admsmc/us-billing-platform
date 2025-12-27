-- Idempotency store for HR write APIs.
--
-- This supports safe retries for POST/PUT/PATCH operations.

CREATE TABLE IF NOT EXISTS hr_idempotency_record (
    employer_id          VARCHAR(64)   NOT NULL,
    operation            VARCHAR(128)  NOT NULL,
    idempotency_key      VARCHAR(256)  NOT NULL,

    request_sha256       VARCHAR(64)   NOT NULL,

    response_status      INT           NOT NULL,
    response_json        TEXT              NULL,

    created_at           TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_hr_idempotency_record PRIMARY KEY (employer_id, operation, idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_hr_idempotency_created_at
    ON hr_idempotency_record (created_at);
