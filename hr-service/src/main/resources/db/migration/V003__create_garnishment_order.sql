-- Per-employee garnishment order lifecycle

CREATE TABLE IF NOT EXISTS garnishment_order (
    employer_id               VARCHAR(64)  NOT NULL,
    employee_id               VARCHAR(64)  NOT NULL,
    order_id                  VARCHAR(128) NOT NULL,

    type                      VARCHAR(64)  NOT NULL,
    issuing_jurisdiction_type VARCHAR(16)      NULL,
    issuing_jurisdiction_code VARCHAR(16)      NULL,
    case_number               VARCHAR(128)     NULL,

    status                    VARCHAR(32)  NOT NULL,
    served_date               DATE             NULL,
    end_date                  DATE             NULL,

    priority_class            INT          NOT NULL DEFAULT 0,
    sequence_within_class     INT          NOT NULL DEFAULT 0,

    initial_arrears_cents     BIGINT           NULL,
    current_arrears_cents     BIGINT           NULL,

    created_at                TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_garnishment_order PRIMARY KEY (employer_id, employee_id, order_id)
);

CREATE INDEX IF NOT EXISTS idx_garnishment_order_employee_status
    ON garnishment_order (employer_id, employee_id, status);
