ALTER TABLE pay_run ADD COLUMN finalize_started_at TIMESTAMP;
ALTER TABLE pay_run ADD COLUMN finalize_completed_at TIMESTAMP;

-- Optional: accelerate queries that filter on completed runs for reporting.
CREATE INDEX IF NOT EXISTS idx_pay_run_finalize_completed_at
  ON pay_run (employer_id, finalize_completed_at);
