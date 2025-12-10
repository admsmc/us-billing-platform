-- Initial tax_rule schema for tax-service.
-- This schema is aligned with docs/tax-schema.md and is designed for Postgres.

CREATE TABLE IF NOT EXISTS tax_rule (
    id                           VARCHAR(100) PRIMARY KEY,

    -- Which employer this rule applies to. NULL means "applicable to all"
    -- employers in the jurisdiction (statutory default). A non-null value
    -- represents an employer-specific overlay or surcharge.
    employer_id                  VARCHAR(64)      NULL,

    jurisdiction_type            VARCHAR(20)      NOT NULL, -- 'FEDERAL' | 'STATE' | 'LOCAL' | 'OTHER'
    jurisdiction_code            VARCHAR(32)      NOT NULL, -- e.g. 'US', 'CA', 'NYC', 'US-ER'

    -- Which basis from the payroll engine this rule applies to
    basis                        VARCHAR(32)      NOT NULL, -- enum name for TaxBasis

    -- High-level rule type.
    rule_type                    VARCHAR(16)      NOT NULL, -- 'FLAT' | 'BRACKETED' | 'WAGE_BRACKET'

    -- Flat-rate specific fields
    rate                         DOUBLE PRECISION     NULL, -- e.g. 0.062 for 6.2%
    annual_wage_cap_cents        BIGINT               NULL, -- e.g. 16020000 for $160,200.00

    -- Bracketed- and wage-bracket-specific fields
    brackets_json                TEXT                 NULL, -- JSON array of TaxBracketConfig
    standard_deduction_cents     BIGINT               NULL,
    additional_withholding_cents BIGINT               NULL,

    -- Effective dating & applicability filters
    effective_from               DATE            NOT NULL,
    effective_to                 DATE            NOT NULL, -- use '9999-12-31' for open-ended

    filing_status                VARCHAR(32)     NULL,     -- e.g. 'SINGLE', 'MARRIED', or NULL = all
    resident_state_filter        VARCHAR(2)      NULL,     -- optional constraint by resident state
    work_state_filter            VARCHAR(2)      NULL,     -- optional constraint by work state
    locality_filter              VARCHAR(32)     NULL,     -- e.g. 'NYC', 'DETROIT'

    -- Metadata for auditing and SCD2-style traceability
    created_at                   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    created_by                   VARCHAR(64)             NOT NULL DEFAULT 'config-importer',
    superseded_by_id             VARCHAR(100)            NULL
);

CREATE INDEX IF NOT EXISTS idx_tax_rule_effective
    ON tax_rule (employer_id, jurisdiction_type, jurisdiction_code, effective_from, effective_to);

CREATE INDEX IF NOT EXISTS idx_tax_rule_filters
    ON tax_rule (filing_status, resident_state_filter, work_state_filter, locality_filter);
