ALTER TABLE paycheck ADD COLUMN run_type VARCHAR(32) NOT NULL DEFAULT 'REGULAR';
ALTER TABLE paycheck ADD COLUMN run_sequence INT NOT NULL DEFAULT 1;

CREATE UNIQUE INDEX uq_paycheck_slot
  ON paycheck (employer_id, employee_id, pay_period_id, run_type, run_sequence);
