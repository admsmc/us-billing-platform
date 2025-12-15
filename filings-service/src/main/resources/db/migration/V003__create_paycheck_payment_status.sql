CREATE TABLE paycheck_payment_status (
  employer_id VARCHAR(128) NOT NULL,
  paycheck_id VARCHAR(128) NOT NULL,

  pay_run_id VARCHAR(128) NOT NULL,
  payment_id VARCHAR(128) NOT NULL,
  status VARCHAR(32) NOT NULL,

  event_id VARCHAR(256) NOT NULL,
  occurred_at TIMESTAMP NOT NULL,

  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,

  PRIMARY KEY (employer_id, paycheck_id)
);

CREATE INDEX idx_payment_status_employer_pay_run ON paycheck_payment_status (employer_id, pay_run_id);
CREATE INDEX idx_payment_status_employer_status ON paycheck_payment_status (employer_id, status);
