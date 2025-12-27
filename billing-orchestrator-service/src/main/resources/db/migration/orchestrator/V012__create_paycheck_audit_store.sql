-- Paycheck audit store (v1)
--
-- Stores a stable-schema PaycheckAudit JSON payload, plus indexed columns for
-- high-performance lookups during disputes/compliance.

CREATE TABLE IF NOT EXISTS paycheck_audit (
    employer_id      VARCHAR(64)  NOT NULL,
    paycheck_id      VARCHAR(128) NOT NULL,

    pay_run_id       VARCHAR(128)     NULL,
    employee_id      VARCHAR(64)  NOT NULL,
    pay_period_id    VARCHAR(128) NOT NULL,
    check_date       DATE         NOT NULL,

    schema_version   INT          NOT NULL,
    engine_version   VARCHAR(64)  NOT NULL,
    computed_at      TIMESTAMP    NOT NULL,

    audit_json       TEXT         NOT NULL,

    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_paycheck_audit PRIMARY KEY (employer_id, paycheck_id)
);

CREATE INDEX IF NOT EXISTS idx_paycheck_audit_employer_employee_check_date
    ON paycheck_audit (employer_id, employee_id, check_date);

CREATE INDEX IF NOT EXISTS idx_paycheck_audit_employer_pay_run
    ON paycheck_audit (employer_id, pay_run_id);
