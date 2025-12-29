-- Add bitemporal fields to case_record for SCD2 compliance
-- This migration converts case_record from mutable to append-only bitemporal

-- Add bitemporal columns
ALTER TABLE case_record
    ADD COLUMN system_from TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN system_to TIMESTAMP NOT NULL DEFAULT TIMESTAMP '9999-12-31 23:59:59',
    ADD COLUMN modified_by VARCHAR(255) NOT NULL DEFAULT 'system:migration';

-- Drop the old primary key
ALTER TABLE case_record DROP CONSTRAINT case_record_pkey;

-- Create new composite primary key (case_id, system_from)
ALTER TABLE case_record ADD PRIMARY KEY (case_id, system_from);

-- Remove the DEFAULT constraints now that initial values are set
ALTER TABLE case_record ALTER COLUMN system_from DROP DEFAULT;
ALTER TABLE case_record ALTER COLUMN system_to DROP DEFAULT;
ALTER TABLE case_record ALTER COLUMN modified_by DROP DEFAULT;

-- Add constraint to ensure system_to > system_from
ALTER TABLE case_record ADD CONSTRAINT chk_case_record_system_range 
    CHECK (system_to > system_from);

-- Create index for finding current versions (system_to = 9999-12-31)
CREATE INDEX idx_case_record_current 
    ON case_record (case_id, system_to) 
    WHERE system_to = TIMESTAMP '9999-12-31 23:59:59';

-- Update existing indexes to include system_to filter for current versions
DROP INDEX IF EXISTS idx_case_utility_status;
CREATE INDEX idx_case_utility_status
    ON case_record (utility_id, status, priority, created_at DESC)
    WHERE system_to = TIMESTAMP '9999-12-31 23:59:59';

DROP INDEX IF EXISTS idx_case_account;
CREATE INDEX idx_case_account
    ON case_record (account_id, status, created_at DESC) 
    WHERE account_id IS NOT NULL AND system_to = TIMESTAMP '9999-12-31 23:59:59';

DROP INDEX IF EXISTS idx_case_customer;
CREATE INDEX idx_case_customer
    ON case_record (customer_id, status, created_at DESC) 
    WHERE customer_id IS NOT NULL AND system_to = TIMESTAMP '9999-12-31 23:59:59';

DROP INDEX IF EXISTS idx_case_assigned_to;
CREATE INDEX idx_case_assigned_to
    ON case_record (assigned_to, status, priority) 
    WHERE assigned_to IS NOT NULL AND system_to = TIMESTAMP '9999-12-31 23:59:59';

DROP INDEX IF EXISTS idx_case_assigned_team;
CREATE INDEX idx_case_assigned_team
    ON case_record (assigned_team, status, priority) 
    WHERE assigned_team IS NOT NULL AND system_to = TIMESTAMP '9999-12-31 23:59:59';

-- Add index for system-time queries (audit/history lookups)
CREATE INDEX idx_case_record_system_time
    ON case_record (case_id, system_from DESC, system_to DESC);

-- Comment documenting the bitemporal pattern
COMMENT ON TABLE case_record IS 
'Bitemporal SCD2 table. All changes create new rows. Current version has system_to = 9999-12-31 23:59:59.';

COMMENT ON COLUMN case_record.system_from IS 
'System time start: when this version became current in the database';

COMMENT ON COLUMN case_record.system_to IS 
'System time end: when this version was superseded. 9999-12-31 for current version.';

COMMENT ON COLUMN case_record.modified_by IS 
'User or system that created this version';
