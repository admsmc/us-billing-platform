-- Bitemporal HR: add system-time versioning to effective-dated compensation.
--
-- Valid time: effective_from/effective_to
-- System time: system_from/system_to

ALTER TABLE employment_compensation
    ADD COLUMN IF NOT EXISTS system_from TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE employment_compensation
    ADD COLUMN IF NOT EXISTS system_to   TIMESTAMP NOT NULL DEFAULT TIMESTAMP '9999-12-31 23:59:59';

-- Postgres does not support `ADD CONSTRAINT IF NOT EXISTS`.
ALTER TABLE employment_compensation
    ADD CONSTRAINT chk_employment_comp_system_range CHECK (system_to > system_from);

CREATE INDEX IF NOT EXISTS idx_employment_comp_system_asof
    ON employment_compensation (employer_id, employee_id, effective_from, effective_to, system_from, system_to);
