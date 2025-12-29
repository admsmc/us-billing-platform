-- Add account_id column to service_point_effective for simplified queries
-- This is a temporary bridge field to support the test API that expects
-- service points to be directly linked to accounts without going through
-- the service_connection table

ALTER TABLE service_point_effective 
ADD COLUMN IF NOT EXISTS account_id VARCHAR(255);

-- Create index for efficient account-based queries
CREATE INDEX IF NOT EXISTS idx_service_point_account_temp 
    ON service_point_effective(account_id, system_from, system_to);

COMMENT ON COLUMN service_point_effective.account_id IS 'Temporary bridge field for account-based queries (not part of production schema)';
