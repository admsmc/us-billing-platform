CREATE TABLE paycheck_payment (
  employer_id VARCHAR(64) NOT NULL,
  payment_id VARCHAR(128) NOT NULL,
  paycheck_id VARCHAR(128) NOT NULL,

  pay_run_id VARCHAR(128) NOT NULL,
  employee_id VARCHAR(64) NOT NULL,
  pay_period_id VARCHAR(128) NOT NULL,

  currency VARCHAR(8) NOT NULL DEFAULT 'USD',
  net_cents BIGINT NOT NULL,

  status VARCHAR(32) NOT NULL DEFAULT 'CREATED',
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,

  PRIMARY KEY (employer_id, payment_id),
  CONSTRAINT uq_payment_by_paycheck UNIQUE (employer_id, paycheck_id)
);

CREATE INDEX idx_payment_by_pay_run ON paycheck_payment (employer_id, pay_run_id);
CREATE INDEX idx_payment_by_status ON paycheck_payment (employer_id, status);
