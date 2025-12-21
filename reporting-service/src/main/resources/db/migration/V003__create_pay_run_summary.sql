CREATE TABLE pay_run_summary (
  employer_id VARCHAR(128) NOT NULL,
  pay_run_id VARCHAR(128) NOT NULL,
  pay_period_id VARCHAR(128) NOT NULL,

  status VARCHAR(64) NOT NULL,
  total INT NOT NULL,
  succeeded INT NOT NULL,
  failed INT NOT NULL,

  event_id VARCHAR(256) NOT NULL,
  occurred_at TIMESTAMP NOT NULL,

  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,

  PRIMARY KEY (employer_id, pay_run_id)
);

CREATE INDEX idx_pay_run_summary_employer_occurred_at ON pay_run_summary (employer_id, occurred_at);
