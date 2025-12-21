CREATE TABLE time_entry (
  employer_id VARCHAR(64) NOT NULL,
  employee_id VARCHAR(64) NOT NULL,
  entry_id VARCHAR(128) NOT NULL,

  work_date DATE NOT NULL,
  hours DOUBLE PRECISION NOT NULL,

  cash_tips_cents BIGINT NOT NULL DEFAULT 0,
  charged_tips_cents BIGINT NOT NULL DEFAULT 0,
  allocated_tips_cents BIGINT NOT NULL DEFAULT 0,

  commission_cents BIGINT NOT NULL DEFAULT 0,
  bonus_cents BIGINT NOT NULL DEFAULT 0,
  reimbursement_non_taxable_cents BIGINT NOT NULL DEFAULT 0,

  worksite_key VARCHAR(128) NULL,

  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

  PRIMARY KEY (employer_id, employee_id, entry_id)
);

CREATE INDEX idx_time_entry_range
  ON time_entry (employer_id, employee_id, work_date);

CREATE INDEX idx_time_entry_employer_date
  ON time_entry (employer_id, work_date);
