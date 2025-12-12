ALTER TABLE paycheck_payment ADD COLUMN batch_id VARCHAR(128);

CREATE INDEX idx_payment_by_batch ON paycheck_payment (employer_id, batch_id);
