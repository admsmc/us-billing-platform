CREATE TABLE pay_run_payment_batch (
  employer_id VARCHAR(64) NOT NULL,
  pay_run_id VARCHAR(128) NOT NULL,
  batch_id VARCHAR(128) NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
  PRIMARY KEY (employer_id, pay_run_id)
);

ALTER TABLE pay_run_payment_batch ADD CONSTRAINT uq_pay_run_payment_batch_batch UNIQUE (employer_id, batch_id);

CREATE INDEX idx_prpb_by_batch ON pay_run_payment_batch (employer_id, batch_id);
