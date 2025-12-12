-- Immutable paycheck store (v1)
--
-- Stores a canonical serialized PaycheckResult JSON payload plus summary columns
-- for high-performance lookup and indexing.

CREATE TABLE IF NOT EXISTS paycheck (
    employer_id      VARCHAR(64)  NOT NULL,
    paycheck_id      VARCHAR(128) NOT NULL,

    pay_run_id       VARCHAR(128)     NULL,
    employee_id      VARCHAR(64)  NOT NULL,
    pay_period_id    VARCHAR(128) NOT NULL,
    check_date       DATE         NOT NULL,

    currency         VARCHAR(8)   NOT NULL DEFAULT 'USD',
    gross_cents      BIGINT       NOT NULL,
    net_cents        BIGINT       NOT NULL,

    status           VARCHAR(32)  NOT NULL DEFAULT 'FINAL',
    version          INT          NOT NULL DEFAULT 1,

    payload_json     CLOB         NOT NULL,

    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_paycheck PRIMARY KEY (employer_id, paycheck_id)
);

CREATE INDEX IF NOT EXISTS idx_paycheck_employer_employee_check_date
    ON paycheck (employer_id, employee_id, check_date);

CREATE INDEX IF NOT EXISTS idx_paycheck_employer_pay_run
    ON paycheck (employer_id, pay_run_id);
