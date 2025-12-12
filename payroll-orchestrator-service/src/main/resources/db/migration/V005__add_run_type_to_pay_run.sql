ALTER TABLE pay_run ADD COLUMN run_type VARCHAR(32) NOT NULL DEFAULT 'REGULAR';
ALTER TABLE pay_run ADD COLUMN run_sequence INT NOT NULL DEFAULT 1;

CREATE UNIQUE INDEX uq_pay_run_employer_period_type_seq
  ON pay_run (employer_id, pay_period_id, run_type, run_sequence);
