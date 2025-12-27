-- Initial labor_standard schema for labor-service (Postgres-oriented).

CREATE TABLE IF NOT EXISTS labor_standard (
    id BIGSERIAL PRIMARY KEY,

    state_code VARCHAR(2) NOT NULL,
    locality_code VARCHAR(32) NULL,
    locality_kind VARCHAR(32) NULL,

    effective_from DATE NOT NULL,
    effective_to DATE NULL,

    regular_minimum_wage_cents BIGINT,
    tipped_minimum_cash_wage_cents BIGINT,
    max_tip_credit_cents BIGINT,

    weekly_ot_threshold_hours DOUBLE PRECISION,
    daily_ot_threshold_hours DOUBLE PRECISION,
    daily_dt_threshold_hours DOUBLE PRECISION
);

CREATE INDEX IF NOT EXISTS idx_labor_standard_effective
    ON labor_standard (state_code, locality_code, effective_from, effective_to);
