-- Bitemporal HR: add system-time versioning to effective-dated employee profiles.
--
-- Valid time: effective_from/effective_to
-- System time: system_from/system_to
--
-- Current row for "now" is defined by: system_from <= CURRENT_TIMESTAMP AND system_to > CURRENT_TIMESTAMP

ALTER TABLE employee_profile_effective
    ADD COLUMN IF NOT EXISTS system_from TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE employee_profile_effective
    ADD COLUMN IF NOT EXISTS system_to   TIMESTAMP NOT NULL DEFAULT TIMESTAMP '9999-12-31 23:59:59';

-- Replace the PK so we can store multiple versions of the same effective_from.
ALTER TABLE employee_profile_effective
    DROP CONSTRAINT IF EXISTS pk_employee_profile_effective;

ALTER TABLE employee_profile_effective
    ADD CONSTRAINT pk_employee_profile_effective_bt PRIMARY KEY (employer_id, employee_id, effective_from, system_from);

-- System-time invariants.
-- Postgres does not support `ADD CONSTRAINT IF NOT EXISTS`.
ALTER TABLE employee_profile_effective
    ADD CONSTRAINT chk_employee_profile_effective_system_range CHECK (system_to > system_from);

-- Rebuild index to include system-time columns for "current row" queries.
DROP INDEX IF EXISTS idx_employee_profile_effective_asof;

CREATE INDEX IF NOT EXISTS idx_employee_profile_effective_asof
    ON employee_profile_effective (employer_id, employee_id, effective_from, effective_to, system_from, system_to);
