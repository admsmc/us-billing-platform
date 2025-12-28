#!/usr/bin/env bash
set -euo pipefail

# Local dev only.
# Creates per-service DBs/users so services can run with least-privilege creds.
#
# Passwords are sourced from environment variables to avoid committing credential-like defaults.
# NOTE: Keep passwords simple (avoid single quotes) or extend escaping below.

: "${POSTGRES_USER:=postgres}"
: "${POSTGRES_DB:=postgres}"

HR_DB_PASSWORD="${HR_DB_PASSWORD:-hr_service}"
TAX_DB_PASSWORD="${TAX_DB_PASSWORD:-tax_service}"
LABOR_DB_PASSWORD="${LABOR_DB_PASSWORD:-labor_service}"
ORCHESTRATOR_DB_PASSWORD="${ORCHESTRATOR_DB_PASSWORD:-orchestrator_service}"
TIME_DB_PASSWORD="${TIME_DB_PASSWORD:-time_service}"

# Billing platform databases
CUSTOMER_DB_PASSWORD="${CUSTOMER_DB_PASSWORD:-customer_service}"
RATE_DB_PASSWORD="${RATE_DB_PASSWORD:-rate_service}"
REGULATORY_DB_PASSWORD="${REGULATORY_DB_PASSWORD:-regulatory_service}"
BILLING_ORCHESTRATOR_DB_PASSWORD="${BILLING_ORCHESTRATOR_DB_PASSWORD:-billing_orchestrator_service}"

# Set password_encryption to md5 for this session
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<SQL
-- Force MD5 password encryption for PgBouncer compatibility
SET password_encryption = 'md5';

-- Reset postgres user password with MD5
ALTER USER postgres WITH PASSWORD '${POSTGRES_PASSWORD:-postgres}';

DO \$\$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'hr_service') THEN
    CREATE USER hr_service WITH PASSWORD '${HR_DB_PASSWORD}';
  END IF;
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'tax_service') THEN
    CREATE USER tax_service WITH PASSWORD '${TAX_DB_PASSWORD}';
  END IF;
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'labor_service') THEN
    CREATE USER labor_service WITH PASSWORD '${LABOR_DB_PASSWORD}';
  END IF;
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'orchestrator_service') THEN
    CREATE USER orchestrator_service WITH PASSWORD '${ORCHESTRATOR_DB_PASSWORD}';
  END IF;
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'time_service') THEN
    CREATE USER time_service WITH PASSWORD '${TIME_DB_PASSWORD}';
  END IF;
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'customer_service') THEN
    CREATE USER customer_service WITH PASSWORD '${CUSTOMER_DB_PASSWORD}';
  END IF;
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'rate_service') THEN
    CREATE USER rate_service WITH PASSWORD '${RATE_DB_PASSWORD}';
  END IF;
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'regulatory_service') THEN
    CREATE USER regulatory_service WITH PASSWORD '${REGULATORY_DB_PASSWORD}';
  END IF;
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'billing_orchestrator_service') THEN
    CREATE USER billing_orchestrator_service WITH PASSWORD '${BILLING_ORCHESTRATOR_DB_PASSWORD}';
  END IF;
END
\$\$;

-- Create databases (idempotent in init context; guarded anyway for readability).
SELECT 'CREATE DATABASE us_payroll_hr OWNER hr_service'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'us_payroll_hr')\gexec

SELECT 'CREATE DATABASE us_payroll_tax OWNER tax_service'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'us_payroll_tax')\gexec

SELECT 'CREATE DATABASE us_payroll_labor OWNER labor_service'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'us_payroll_labor')\gexec

SELECT 'CREATE DATABASE us_payroll_orchestrator OWNER orchestrator_service'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'us_payroll_orchestrator')\gexec

SELECT 'CREATE DATABASE us_payroll_time OWNER time_service'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'us_payroll_time')\gexec

-- Billing platform databases
SELECT 'CREATE DATABASE us_billing_customer OWNER customer_service'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'us_billing_customer')\gexec

SELECT 'CREATE DATABASE us_billing_rate OWNER rate_service'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'us_billing_rate')\gexec

SELECT 'CREATE DATABASE us_billing_regulatory OWNER regulatory_service'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'us_billing_regulatory')\gexec

SELECT 'CREATE DATABASE us_billing_orchestrator OWNER billing_orchestrator_service'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'us_billing_orchestrator')\gexec

GRANT ALL PRIVILEGES ON DATABASE us_payroll_hr TO hr_service;
GRANT ALL PRIVILEGES ON DATABASE us_payroll_tax TO tax_service;
GRANT ALL PRIVILEGES ON DATABASE us_payroll_labor TO labor_service;
GRANT ALL PRIVILEGES ON DATABASE us_payroll_orchestrator TO orchestrator_service;
GRANT ALL PRIVILEGES ON DATABASE us_payroll_time TO time_service;

GRANT ALL PRIVILEGES ON DATABASE us_billing_customer TO customer_service;
GRANT ALL PRIVILEGES ON DATABASE us_billing_rate TO rate_service;
GRANT ALL PRIVILEGES ON DATABASE us_billing_regulatory TO regulatory_service;
GRANT ALL PRIVILEGES ON DATABASE us_billing_orchestrator TO billing_orchestrator_service;
SQL
