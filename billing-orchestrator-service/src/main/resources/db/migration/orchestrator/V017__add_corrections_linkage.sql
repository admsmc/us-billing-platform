ALTER TABLE pay_run
    ADD COLUMN correction_of_pay_run_id VARCHAR(128);

ALTER TABLE paycheck
    ADD COLUMN correction_of_paycheck_id VARCHAR(128);

CREATE INDEX IF NOT EXISTS idx_pay_run_correction_of
    ON pay_run (employer_id, correction_of_pay_run_id);

CREATE INDEX IF NOT EXISTS idx_paycheck_correction_of
    ON paycheck (employer_id, correction_of_paycheck_id);
