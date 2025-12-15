CREATE TABLE paycheck_ledger_entry (
  employer_id VARCHAR(128) NOT NULL,
  paycheck_id VARCHAR(128) NOT NULL,
  employee_id VARCHAR(128) NOT NULL,
  pay_run_id VARCHAR(128) NOT NULL,
  pay_run_type VARCHAR(64) NOT NULL,
  run_sequence INT NOT NULL,
  pay_period_id VARCHAR(128) NOT NULL,

  check_date DATE NOT NULL,
  action VARCHAR(32) NOT NULL,

  currency VARCHAR(8) NOT NULL,
  gross_cents BIGINT NOT NULL,
  net_cents BIGINT NOT NULL,

  event_id VARCHAR(256) NOT NULL,
  occurred_at TIMESTAMP NOT NULL,

  audit_json TEXT,
  payload_json TEXT NOT NULL,

  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,

  PRIMARY KEY (employer_id, paycheck_id)
);

CREATE INDEX idx_ledger_employer_check_date ON paycheck_ledger_entry (employer_id, check_date);
CREATE INDEX idx_ledger_employer_employee_check_date ON paycheck_ledger_entry (employer_id, employee_id, check_date);
CREATE INDEX idx_ledger_employer_pay_run ON paycheck_ledger_entry (employer_id, pay_run_id);
