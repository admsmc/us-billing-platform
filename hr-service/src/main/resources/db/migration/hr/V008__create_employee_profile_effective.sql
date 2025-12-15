-- Effective-dated employee profile (valid-time)
--
-- This table allows HR attributes that affect payroll correctness (filing status, work/home location,
-- FLSA/tipped flags, W-4 values, etc.) to be queried deterministically "as of" a date.

CREATE TABLE IF NOT EXISTS employee_profile_effective (
    employer_id                  VARCHAR(64)  NOT NULL,
    employee_id                  VARCHAR(64)  NOT NULL,

    effective_from               DATE         NOT NULL,
    effective_to                 DATE         NOT NULL,

    home_state                   CHAR(2)      NOT NULL,
    work_state                   CHAR(2)      NOT NULL,
    work_city                    VARCHAR(100)     NULL,

    filing_status                VARCHAR(32)  NOT NULL,
    employment_type              VARCHAR(32)  NOT NULL,

    hire_date                    DATE             NULL,
    termination_date             DATE             NULL,

    dependents                   INTEGER          NULL,

    federal_withholding_exempt   BOOLEAN      NOT NULL,
    is_nonresident_alien         BOOLEAN      NOT NULL,

    w4_annual_credit_cents       BIGINT           NULL,
    w4_other_income_cents        BIGINT           NULL,
    w4_deductions_cents          BIGINT           NULL,
    w4_step2_multiple_jobs       BOOLEAN      NOT NULL,

    w4_version                   VARCHAR(20)      NULL,
    legacy_allowances            INTEGER          NULL,
    legacy_additional_withholding_cents BIGINT    NULL,
    legacy_marital_status        VARCHAR(32)      NULL,
    w4_effective_date            DATE             NULL,

    additional_withholding_cents BIGINT           NULL,

    fica_exempt                  BOOLEAN      NOT NULL,
    flsa_enterprise_covered      BOOLEAN      NOT NULL,
    flsa_exempt_status           VARCHAR(32)  NOT NULL,
    is_tipped_employee           BOOLEAN      NOT NULL,

    created_at                   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_employee_profile_effective PRIMARY KEY (employer_id, employee_id, effective_from),
    CONSTRAINT fk_employee_profile_employee FOREIGN KEY (employer_id, employee_id)
        REFERENCES employee (employer_id, employee_id),
    CONSTRAINT chk_employee_profile_effective_range CHECK (effective_to > effective_from)
);

CREATE INDEX IF NOT EXISTS idx_employee_profile_effective_asof
    ON employee_profile_effective (employer_id, employee_id, effective_from, effective_to);

-- Backfill a single open-ended profile row per employee, if not already present.
-- Choose a conservative effective_from derived from W-4 effective date / hire_date, else 1900-01-01.
INSERT INTO employee_profile_effective (
    employer_id,
    employee_id,
    effective_from,
    effective_to,
    home_state,
    work_state,
    work_city,
    filing_status,
    employment_type,
    hire_date,
    termination_date,
    dependents,
    federal_withholding_exempt,
    is_nonresident_alien,
    w4_annual_credit_cents,
    w4_other_income_cents,
    w4_deductions_cents,
    w4_step2_multiple_jobs,
    w4_version,
    legacy_allowances,
    legacy_additional_withholding_cents,
    legacy_marital_status,
    w4_effective_date,
    additional_withholding_cents,
    fica_exempt,
    flsa_enterprise_covered,
    flsa_exempt_status,
    is_tipped_employee
)
SELECT
    e.employer_id,
    e.employee_id,
    COALESCE(e.w4_effective_date, e.hire_date, DATE '1900-01-01') AS effective_from,
    DATE '9999-12-31' AS effective_to,
    e.home_state,
    e.work_state,
    e.work_city,
    e.filing_status,
    e.employment_type,
    e.hire_date,
    e.termination_date,
    e.dependents,
    e.federal_withholding_exempt,
    e.is_nonresident_alien,
    e.w4_annual_credit_cents,
    e.w4_other_income_cents,
    e.w4_deductions_cents,
    e.w4_step2_multiple_jobs,
    e.w4_version,
    e.legacy_allowances,
    e.legacy_additional_withholding_cents,
    e.legacy_marital_status,
    e.w4_effective_date,
    e.additional_withholding_cents,
    e.fica_exempt,
    e.flsa_enterprise_covered,
    e.flsa_exempt_status,
    e.is_tipped_employee
FROM employee e
WHERE NOT EXISTS (
    SELECT 1
    FROM employee_profile_effective p
    WHERE p.employer_id = e.employer_id
      AND p.employee_id = e.employee_id
);
