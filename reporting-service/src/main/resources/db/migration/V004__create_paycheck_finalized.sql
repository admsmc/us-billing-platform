CREATE TABLE paycheck_finalized (
  employer_id VARCHAR(128) NOT NULL,
  paycheck_id VARCHAR(128) NOT NULL,
  employee_id VARCHAR(128) NOT NULL,
  pay_run_id VARCHAR(128) NOT NULL,

  -- Populated when a PaycheckLedger event is later ingested (may remain null otherwise).
  pay_period_id VARCHAR(128),
  pay_run_type VARCHAR(64),
  run_sequence INT,
  check_date DATE,

  event_id VARCHAR(256) NOT NULL,
  occurred_at TIMESTAMP NOT NULL,

  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,

  PRIMARY KEY (employer_id, paycheck_id)
);

CREATE INDEX idx_paycheck_finalized_employer_employee ON paycheck_finalized (employer_id, employee_id);
CREATE INDEX idx_paycheck_finalized_employer_pay_run ON paycheck_finalized (employer_id, pay_run_id);
CREATE INDEX idx_paycheck_finalized_employer_check_date ON paycheck_finalized (employer_id, check_date);
