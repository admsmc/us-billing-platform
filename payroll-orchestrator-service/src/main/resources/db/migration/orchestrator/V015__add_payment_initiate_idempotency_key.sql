ALTER TABLE pay_run ADD COLUMN payment_initiate_idempotency_key VARCHAR(128);

-- Allow clients to safely retry payment initiation with an Idempotency-Key.
-- Nullable unique constraint: multiple NULLs allowed; non-null must be unique per employer.
ALTER TABLE pay_run ADD CONSTRAINT uq_pay_run_payment_initiate_idempotency UNIQUE (employer_id, payment_initiate_idempotency_key);