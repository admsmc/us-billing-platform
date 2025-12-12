CREATE TABLE pay_run (
  employer_id VARCHAR(64) NOT NULL,
  pay_run_id VARCHAR(128) NOT NULL,
  pay_period_id VARCHAR(128) NOT NULL,
  status VARCHAR(32) NOT NULL,

  requested_idempotency_key VARCHAR(128),

  lease_owner VARCHAR(128),
  lease_expires_at_epoch_ms BIGINT,

  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,

  PRIMARY KEY (employer_id, pay_run_id),
  CONSTRAINT uq_pay_run_idempotency UNIQUE (employer_id, requested_idempotency_key)
);

CREATE INDEX idx_pay_run_status ON pay_run (employer_id, status);

CREATE TABLE pay_run_item (
  employer_id VARCHAR(64) NOT NULL,
  pay_run_id VARCHAR(128) NOT NULL,
  employee_id VARCHAR(64) NOT NULL,

  status VARCHAR(32) NOT NULL,
  paycheck_id VARCHAR(128),

  attempt_count INT DEFAULT 0 NOT NULL,
  last_error VARCHAR(2000),

  started_at TIMESTAMP,
  completed_at TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,

  PRIMARY KEY (employer_id, pay_run_id, employee_id),
  CONSTRAINT fk_pay_run_item_pay_run FOREIGN KEY (employer_id, pay_run_id)
    REFERENCES pay_run (employer_id, pay_run_id)
);

CREATE INDEX idx_pay_run_item_status ON pay_run_item (employer_id, pay_run_id, status);
