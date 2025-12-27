-- Fix indexes from V018 which may have failed with CONCURRENT
-- Drop any invalid/incomplete indexes and recreate without CONCURRENT (table is empty/small)

DROP INDEX IF EXISTS idx_pay_run_item_counts;
DROP INDEX IF EXISTS idx_pay_run_item_active;
DROP INDEX IF EXISTS idx_pay_run_item_queued;
DROP INDEX IF EXISTS idx_pay_run_item_failed;

-- Covering index for count queries
CREATE INDEX idx_pay_run_item_counts 
ON pay_run_item (employer_id, pay_run_id, status)
INCLUDE (employee_id);

-- Partial index for active items
CREATE INDEX idx_pay_run_item_active
ON pay_run_item (employer_id, pay_run_id, status, updated_at)
WHERE status IN ('QUEUED', 'RUNNING');

-- Index for claim operations
CREATE INDEX idx_pay_run_item_queued
ON pay_run_item (employer_id, pay_run_id, status, employee_id)
WHERE status = 'QUEUED';

-- Index for failure queries
CREATE INDEX idx_pay_run_item_failed
ON pay_run_item (employer_id, pay_run_id, status)
INCLUDE (employee_id, last_error)
WHERE status = 'FAILED';
