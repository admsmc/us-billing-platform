CREATE TABLE payment_batch (
  employer_id VARCHAR(64) NOT NULL,
  batch_id VARCHAR(128) NOT NULL,

  pay_run_id VARCHAR(128) NOT NULL,

  status VARCHAR(32) NOT NULL DEFAULT 'CREATED',

  total_payments INT NOT NULL DEFAULT 0,
  settled_payments INT NOT NULL DEFAULT 0,
  failed_payments INT NOT NULL DEFAULT 0,

  attempts INT NOT NULL DEFAULT 0,
  next_attempt_at TIMESTAMP,
  last_error VARCHAR(2000),

  locked_by VARCHAR(128),
  locked_at TIMESTAMP,

  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,

  PRIMARY KEY (employer_id, batch_id)
);

CREATE INDEX idx_payment_batch_by_pay_run ON payment_batch (employer_id, pay_run_id);
CREATE INDEX idx_payment_batch_by_status ON payment_batch (employer_id, status);
