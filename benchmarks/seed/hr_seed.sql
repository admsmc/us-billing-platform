-- Benchmark seed for hr-service (Postgres).
--
-- This is intentionally idempotent and scoped to a single employer so it can be
-- re-run safely.
--
-- Required psql variables:
--   :employer_id
--   :pay_period_id
--   :start_date (YYYY-MM-DD)
--   :end_date   (YYYY-MM-DD)
--   :check_date (YYYY-MM-DD)
--   :employee_count (integer)
--
-- Optional:
--   :home_state (default CA)        - used for non-MI employees
--   :work_state (default CA)        - used for non-MI employees
--   :work_city  (default San Francisco) - used for non-MI employees

-- Ensure optional variables are always defined (psql will error on :''var'' if undefined).
\if :{?home_state}
\else
\set home_state ''
\endif
\if :{?work_state}
\else
\set work_state ''
\endif
\if :{?work_city}
\else
\set work_city ''
\endif
\if :{?mi_every}
\else
\set mi_every 5
\endif
\if :{?ny_every}
\else
\set ny_every 7
\endif
\if :{?ca_hourly_every}
\else
\set ca_hourly_every 9
\endif
\if :{?garnishment_every}
\else
\set garnishment_every 3
\endif
\if :{?tipped_every}
\else
\set tipped_every 11
\endif
\if :{?realistic_profile}
\else
\set realistic_profile 0
\endif
--
-- Mixed population controls:
--   :mi_every (default 5)           - every Nth employee is seeded as MI (hourly + MI city)
--   :ny_every (default 7)           - every Nth employee is seeded as NY (hourly)
--   :ca_hourly_every (default 9)    - every Nth employee is seeded as CA (hourly)
--   :tipped_every (default 11)      - every Nth employee is marked as a tipped employee (tip-credit logic only applies to hourly)
--   :garnishment_every (default 3)  - every Nth employee receives at least one ACTIVE garnishment order
--   :realistic_profile (0/1)        - when 1, use more realistic filing status, dependents, and salary mixes

BEGIN;

-- Clean existing benchmark rows.
-- Note: order matters due to FK constraints.
DELETE FROM garnishment_withholding_event WHERE employer_id = :'employer_id';
DELETE FROM garnishment_ledger WHERE employer_id = :'employer_id';
DELETE FROM garnishment_order WHERE employer_id = :'employer_id';
DELETE FROM employment_compensation WHERE employer_id = :'employer_id';
DELETE FROM employee_profile_effective WHERE employer_id = :'employer_id';
DELETE FROM employee WHERE employer_id = :'employer_id';
DELETE FROM pay_period WHERE employer_id = :'employer_id';

-- One pay period.
INSERT INTO pay_period (
  employer_id, id,
  start_date, end_date, check_date,
  frequency, sequence_in_year
) VALUES (
  :'employer_id', :'pay_period_id',
  :'start_date'::date, :'end_date'::date, :'check_date'::date,
  'BIWEEKLY', 1
);

-- Generate N employees + matching effective-dated profiles + compensation.
-- Employee IDs will be: EE-BENCH-000001 .. EE-BENCH-<N>
WITH seed AS (
  SELECT
    generate_series(1, :'employee_count'::int) AS n,
    (:'mi_every'::int) AS mi_every,
    (:'ny_every'::int) AS ny_every,
    (:'ca_hourly_every'::int) AS ca_hourly_every,
    (:'tipped_every'::int) AS tipped_every
)
INSERT INTO employee (
  employer_id, employee_id,
  first_name, last_name,
  home_state, work_state, work_city,
  filing_status, employment_type,
  hire_date, termination_date,
  dependents,
  federal_withholding_exempt, is_nonresident_alien,
  w4_annual_credit_cents, w4_other_income_cents, w4_deductions_cents,
  w4_step2_multiple_jobs,
  additional_withholding_cents,
  fica_exempt, flsa_enterprise_covered, flsa_exempt_status, is_tipped_employee
) SELECT
  :'employer_id',
  format('EE-BENCH-%s', lpad(n::text, 6, '0')),
  'Bench',
  format('Employee%s', lpad(n::text, 6, '0')),
  CASE
    WHEN (n % ny_every) = 0 THEN 'NY'
    WHEN (n % mi_every) = 0 THEN 'MI'
    WHEN (n % ca_hourly_every) = 0 THEN 'CA'
    ELSE COALESCE(NULLIF(:'home_state', ''), 'CA')
  END,
  CASE
    WHEN (n % ny_every) = 0 THEN 'NY'
    WHEN (n % mi_every) = 0 THEN 'MI'
    WHEN (n % ca_hourly_every) = 0 THEN 'CA'
    ELSE COALESCE(NULLIF(:'work_state', ''), 'CA')
  END,
  CASE
    WHEN (n % ny_every) = 0 THEN 'New York'
    WHEN (n % mi_every) = 0 THEN
      CASE (n % (mi_every * 3))
        WHEN 0 THEN 'Detroit'
        WHEN mi_every THEN 'Grand Rapids'
        ELSE 'Lansing'
      END
    WHEN (n % ca_hourly_every) = 0 THEN 'Los Angeles'
    ELSE COALESCE(NULLIF(:'work_city', ''), 'San Francisco')
  END,
  CASE
    WHEN (:'realistic_profile'::int) = 1 THEN
      CASE
        WHEN (n % 10) IN (0, 1, 2, 3, 4) THEN 'SINGLE'                     -- ~50%
        WHEN (n % 10) IN (5, 6, 7) THEN 'MARRIED_FILING_JOINTLY'          -- ~30%
        WHEN (n % 10) = 8 THEN 'HEAD_OF_HOUSEHOLD'                        -- ~10%
        ELSE 'MARRIED_FILING_SEPARATELY'                                  -- ~10%
      END
    ELSE 'SINGLE'
  END,
  'REGULAR',
  :'start_date'::date,
  NULL,
  CASE
    WHEN (:'realistic_profile'::int) = 1 THEN
      CASE
        WHEN (n % 10) IN (0, 1, 2, 3) THEN 0
        WHEN (n % 10) IN (4, 5, 6) THEN 1
        WHEN (n % 10) IN (7, 8) THEN 2
        ELSE 3
      END
    ELSE 0
  END,
  FALSE,
  FALSE,
  NULL,
  NULL,
  NULL,
  FALSE,
  NULL,
  FALSE,
  TRUE,
  'NON_EXEMPT',
  CASE WHEN (tipped_every > 0 AND (n % tipped_every) = 0) THEN TRUE ELSE FALSE END
FROM seed;

-- IMPORTANT: the HR snapshot endpoint uses employee_profile_effective.
-- effective_to is treated as exclusive (effective_to > asOf), so use a far-future date.
WITH seed AS (
  SELECT
    generate_series(1, :'employee_count'::int) AS n,
    (:'mi_every'::int) AS mi_every,
    (:'ny_every'::int) AS ny_every,
    (:'ca_hourly_every'::int) AS ca_hourly_every,
    (:'tipped_every'::int) AS tipped_every
)
INSERT INTO employee_profile_effective (
  employer_id, employee_id,
  effective_from, effective_to,
  home_state, work_state, work_city,
  filing_status, employment_type,
  hire_date, termination_date,
  dependents,
  federal_withholding_exempt, is_nonresident_alien,
  w4_annual_credit_cents, w4_other_income_cents, w4_deductions_cents,
  w4_step2_multiple_jobs,
  additional_withholding_cents,
  fica_exempt, flsa_enterprise_covered, flsa_exempt_status, is_tipped_employee
) SELECT
  :'employer_id',
  format('EE-BENCH-%s', lpad(n::text, 6, '0')),
  :'start_date'::date,
  '9999-12-31'::date,
  CASE
    WHEN (n % ny_every) = 0 THEN 'NY'
    WHEN (n % mi_every) = 0 THEN 'MI'
    WHEN (n % ca_hourly_every) = 0 THEN 'CA'
    ELSE COALESCE(NULLIF(:'home_state', ''), 'CA')
  END,
  CASE
    WHEN (n % ny_every) = 0 THEN 'NY'
    WHEN (n % mi_every) = 0 THEN 'MI'
    WHEN (n % ca_hourly_every) = 0 THEN 'CA'
    ELSE COALESCE(NULLIF(:'work_state', ''), 'CA')
  END,
  CASE
    WHEN (n % ny_every) = 0 THEN 'New York'
    WHEN (n % mi_every) = 0 THEN
      CASE (n % (mi_every * 3))
        WHEN 0 THEN 'Detroit'
        WHEN mi_every THEN 'Grand Rapids'
        ELSE 'Lansing'
      END
    WHEN (n % ca_hourly_every) = 0 THEN 'Los Angeles'
    ELSE COALESCE(NULLIF(:'work_city', ''), 'San Francisco')
  END,
  CASE
    WHEN (:'realistic_profile'::int) = 1 THEN
      CASE
        WHEN (n % 10) IN (0, 1, 2, 3, 4) THEN 'SINGLE'
        WHEN (n % 10) IN (5, 6, 7) THEN 'MARRIED_FILING_JOINTLY'
        WHEN (n % 10) = 8 THEN 'HEAD_OF_HOUSEHOLD'
        ELSE 'MARRIED_FILING_SEPARATELY'
      END
    ELSE 'SINGLE'
  END,
  'REGULAR',
  :'start_date'::date,
  NULL,
  CASE
    WHEN (:'realistic_profile'::int) = 1 THEN
      CASE
        WHEN (n % 10) IN (0, 1, 2, 3) THEN 0
        WHEN (n % 10) IN (4, 5, 6) THEN 1
        WHEN (n % 10) IN (7, 8) THEN 2
        ELSE 3
      END
    ELSE 0
  END,
  FALSE,
  FALSE,
  NULL,
  NULL,
  NULL,
  FALSE,
  NULL,
  FALSE,
  TRUE,
  'NON_EXEMPT',
  CASE WHEN (tipped_every > 0 AND (n % tipped_every) = 0) THEN TRUE ELSE FALSE END
FROM seed;

WITH seed AS (
  SELECT
    generate_series(1, :'employee_count'::int) AS n,
    (:'mi_every'::int) AS mi_every,
    (:'ny_every'::int) AS ny_every,
    (:'ca_hourly_every'::int) AS ca_hourly_every
)
INSERT INTO employment_compensation (
  employer_id, employee_id,
  effective_from, effective_to,
  compensation_type,
  annual_salary_cents, hourly_rate_cents, pay_frequency
) SELECT
  :'employer_id',
  format('EE-BENCH-%s', lpad(n::text, 6, '0')),
  :'start_date'::date,
  '9999-12-31'::date,
  CASE
    WHEN (n % ny_every) = 0 THEN 'HOURLY'
    WHEN (n % mi_every) = 0 THEN 'HOURLY'
    WHEN (n % ca_hourly_every) = 0 THEN 'HOURLY'
    ELSE 'SALARIED'
  END,
  CASE
    WHEN (n % ny_every) = 0 THEN NULL
    WHEN (n % mi_every) = 0 THEN NULL
    WHEN (n % ca_hourly_every) = 0 THEN NULL
    ELSE
      CASE
        WHEN (:'realistic_profile'::int) = 1 THEN
          CASE (n % 10)
            WHEN 0 THEN 2500000   -- $25,000
            WHEN 1 THEN 3000000   -- $30,000
            WHEN 2 THEN 4000000   -- $40,000
            WHEN 3 THEN 5000000   -- $50,000
            WHEN 4 THEN 6000000   -- $60,000
            WHEN 5 THEN 8000000   -- $80,000
            WHEN 6 THEN 10000000  -- $100,000
            WHEN 7 THEN 13000000  -- $130,000
            WHEN 8 THEN 16000000  -- $160,000
            ELSE 20000000         -- $200,000
          END
        ELSE
          CASE (n % 8)
            WHEN 0 THEN 4500000   -- $45,000
            WHEN 1 THEN 6000000   -- $60,000
            WHEN 2 THEN 8000000   -- $80,000
            WHEN 3 THEN 10000000  -- $100,000
            WHEN 4 THEN 13000000  -- $130,000
            WHEN 5 THEN 16000000  -- $160,000
            WHEN 6 THEN 20000000  -- $200,000
            ELSE 5200000          -- $52,000
          END
      END
  END,
  CASE
    WHEN (n % ny_every) = 0 THEN CASE (n % 4)
      WHEN 0 THEN 2800 -- $28/hr
      WHEN 1 THEN 3200 -- $32/hr
      WHEN 2 THEN 3800 -- $38/hr
      ELSE 4500        -- $45/hr
    END
    WHEN (n % mi_every) = 0 THEN CASE (n % 4)
      WHEN 0 THEN 2000 -- $20/hr
      WHEN 1 THEN 2400 -- $24/hr
      WHEN 2 THEN 2800 -- $28/hr
      ELSE 3200        -- $32/hr
    END
    WHEN (n % ca_hourly_every) = 0 THEN CASE (n % 4)
      WHEN 0 THEN 3000 -- $30/hr
      WHEN 1 THEN 3600 -- $36/hr
      WHEN 2 THEN 4200 -- $42/hr
      ELSE 5200        -- $52/hr
    END
    ELSE NULL
  END,
  'BIWEEKLY'
FROM seed;

-- Garnishment orders (subset of employees) so paycheck generation includes:
-- - garnishment calculation
-- - HR withholding callbacks (when using worker-service HR-backed flow)
WITH seed AS (
  SELECT
    generate_series(1, :'employee_count'::int) AS n,
    (:'garnishment_every'::int) AS g_every,
    (:'start_date'::date) AS served_date
)
INSERT INTO garnishment_order (
  employer_id, employee_id, order_id,
  type,
  issuing_jurisdiction_type, issuing_jurisdiction_code,
  case_number,
  status,
  served_date,
  end_date,
  priority_class,
  sequence_within_class,
  initial_arrears_cents,
  current_arrears_cents,
  supports_other_dependents,
  arrears_at_least_12_weeks,
  -- typed overrides
  formula_type,
  percent_of_disposable,
  fixed_amount_cents,
  protected_floor_cents,
  protected_min_wage_hourly_rate_cents,
  protected_min_wage_hours,
  protected_min_wage_multiplier,
  -- json escape hatch
  formula_json,
  protected_earnings_rule_json
) SELECT
  :'employer_id',
  format('EE-BENCH-%s', lpad(n::text, 6, '0')),
  format('ORDER-BENCH-%s-A', lpad(n::text, 6, '0')),
  CASE
    WHEN (n % 2) = 0 THEN 'CREDITOR_GARNISHMENT'
    ELSE 'CHILD_SUPPORT'
  END,
  'STATE',
  'MI',
  format('CASE-%s', lpad(n::text, 6, '0')),
  'ACTIVE',
  served_date,
  NULL,
  0,
  0,
  NULL,
  NULL,
  CASE WHEN (n % 2) = 0 THEN NULL ELSE TRUE END,
  CASE WHEN (n % 2) = 0 THEN NULL ELSE FALSE END,
  CASE
    WHEN (n % 2) = 0 THEN 'PERCENT_OF_DISPOSABLE'
    ELSE 'PERCENT_OF_DISPOSABLE'
  END,
  CASE
    WHEN (n % 2) = 0 THEN 0.10
    ELSE 0.25
  END,
  NULL,
  CASE
    -- For child support, apply a protected floor so we hit protected earnings behavior.
    WHEN (n % 2) = 0 THEN NULL
    ELSE 50000
  END,
  NULL,
  NULL,
  NULL,
  NULL,
  NULL
FROM seed
WHERE (n % g_every) = 0;

-- Add a second order for a smaller subset to exercise priority and multiple-order handling.
WITH seed AS (
  SELECT
    generate_series(1, :'employee_count'::int) AS n,
    (:'garnishment_every'::int) AS g_every,
    (:'start_date'::date) AS served_date
)
INSERT INTO garnishment_order (
  employer_id, employee_id, order_id,
  type,
  issuing_jurisdiction_type, issuing_jurisdiction_code,
  case_number,
  status,
  served_date,
  end_date,
  priority_class,
  sequence_within_class,
  initial_arrears_cents,
  current_arrears_cents,
  supports_other_dependents,
  arrears_at_least_12_weeks,
  formula_type,
  percent_of_disposable,
  fixed_amount_cents,
  protected_floor_cents,
  protected_min_wage_hourly_rate_cents,
  protected_min_wage_hours,
  protected_min_wage_multiplier,
  formula_json,
  protected_earnings_rule_json
) SELECT
  :'employer_id',
  format('EE-BENCH-%s', lpad(n::text, 6, '0')),
  format('ORDER-BENCH-%s-B', lpad(n::text, 6, '0')),
  'CREDITOR_GARNISHMENT',
  'STATE',
  'MI',
  format('CASE-B-%s', lpad(n::text, 6, '0')),
  'ACTIVE',
  served_date,
  NULL,
  1,
  0,
  NULL,
  NULL,
  NULL,
  NULL,
  'FIXED_AMOUNT_PER_PERIOD',
  NULL,
  5000,
  NULL,
  NULL,
  NULL,
  NULL,
  NULL,
  NULL
FROM seed
WHERE (n % (g_every * 5)) = 0;

-- Ledger rows (so HR has a consistent lifecycle record).
INSERT INTO garnishment_ledger (
  employer_id,
  employee_id,
  order_id,
  total_withheld_cents,
  initial_arrears_cents,
  remaining_arrears_cents,
  last_check_date,
  last_paycheck_id,
  last_pay_run_id
)
SELECT
  go.employer_id,
  go.employee_id,
  go.order_id,
  0,
  go.initial_arrears_cents,
  go.current_arrears_cents,
  NULL,
  NULL,
  NULL
FROM garnishment_order go
WHERE go.employer_id = :'employer_id';

COMMIT;
