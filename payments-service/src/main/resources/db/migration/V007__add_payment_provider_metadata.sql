ALTER TABLE payment_batch
  ADD COLUMN provider VARCHAR(64) NOT NULL DEFAULT 'SANDBOX';

ALTER TABLE payment_batch
  ADD COLUMN provider_batch_ref VARCHAR(256);

ALTER TABLE paycheck_payment
  ADD COLUMN provider VARCHAR(64) NOT NULL DEFAULT 'SANDBOX';

ALTER TABLE paycheck_payment
  ADD COLUMN provider_payment_ref VARCHAR(256);

CREATE INDEX idx_payment_batch_by_provider ON payment_batch (employer_id, provider);
CREATE INDEX idx_paycheck_payment_by_provider ON paycheck_payment (employer_id, provider);
