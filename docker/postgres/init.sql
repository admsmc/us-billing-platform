-- Local dev only.
-- Creates per-service DBs/users so services can run with least-privilege creds.

CREATE USER hr_service WITH PASSWORD 'hr_service';
CREATE USER tax_service WITH PASSWORD 'tax_service';
CREATE USER labor_service WITH PASSWORD 'labor_service';

CREATE DATABASE us_payroll_hr OWNER hr_service;
CREATE DATABASE us_payroll_tax OWNER tax_service;
CREATE DATABASE us_payroll_labor OWNER labor_service;

GRANT ALL PRIVILEGES ON DATABASE us_payroll_hr TO hr_service;
GRANT ALL PRIVILEGES ON DATABASE us_payroll_tax TO tax_service;
GRANT ALL PRIVILEGES ON DATABASE us_payroll_labor TO labor_service;
