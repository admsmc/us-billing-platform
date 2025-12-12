-- Constraints for typed (queryable) garnishment override columns.
--
-- These are written to be permissive when overrides are not used:
--   - formula_type is NULL => no typed formula override.
--   - protected_* columns all NULL => no typed protected earnings override.
--
-- When overrides are present, enforce structural consistency so that both
-- DB-level integrity and downstream mapping logic remain predictable.

ALTER TABLE garnishment_order
    ADD CONSTRAINT chk_garnishment_order_percent_range
    CHECK (
        percent_of_disposable IS NULL OR (percent_of_disposable >= 0.0 AND percent_of_disposable <= 1.0)
    );

ALTER TABLE garnishment_order
    ADD CONSTRAINT chk_garnishment_order_fixed_amount_positive
    CHECK (
        fixed_amount_cents IS NULL OR fixed_amount_cents >= 0
    );

ALTER TABLE garnishment_order
    ADD CONSTRAINT chk_garnishment_order_typed_formula
    CHECK (
        formula_type IS NULL OR (
            (formula_type = 'PERCENT_OF_DISPOSABLE' AND percent_of_disposable IS NOT NULL AND fixed_amount_cents IS NULL) OR
            (formula_type = 'FIXED_AMOUNT_PER_PERIOD' AND fixed_amount_cents IS NOT NULL AND percent_of_disposable IS NULL) OR
            (formula_type = 'LESSER_OF_PERCENT_OR_AMOUNT' AND percent_of_disposable IS NOT NULL AND fixed_amount_cents IS NOT NULL) OR
            (formula_type = 'LEVY_WITH_BANDS' AND percent_of_disposable IS NULL AND fixed_amount_cents IS NULL)
        )
    );

ALTER TABLE garnishment_order
    ADD CONSTRAINT chk_garnishment_order_protected_floor_positive
    CHECK (
        protected_floor_cents IS NULL OR protected_floor_cents >= 0
    );

ALTER TABLE garnishment_order
    ADD CONSTRAINT chk_garnishment_order_min_wage_positive
    CHECK (
        protected_min_wage_hourly_rate_cents IS NULL OR protected_min_wage_hourly_rate_cents >= 0
    );

ALTER TABLE garnishment_order
    ADD CONSTRAINT chk_garnishment_order_min_wage_hours_positive
    CHECK (
        protected_min_wage_hours IS NULL OR protected_min_wage_hours >= 0.0
    );

ALTER TABLE garnishment_order
    ADD CONSTRAINT chk_garnishment_order_min_wage_multiplier_positive
    CHECK (
        protected_min_wage_multiplier IS NULL OR protected_min_wage_multiplier >= 0.0
    );

ALTER TABLE garnishment_order
    ADD CONSTRAINT chk_garnishment_order_typed_protected_earnings
    CHECK (
        -- no override
        (
            protected_floor_cents IS NULL AND
            protected_min_wage_hourly_rate_cents IS NULL AND
            protected_min_wage_hours IS NULL AND
            protected_min_wage_multiplier IS NULL
        ) OR
        -- FixedFloor override
        (
            protected_floor_cents IS NOT NULL AND
            protected_min_wage_hourly_rate_cents IS NULL AND
            protected_min_wage_hours IS NULL AND
            protected_min_wage_multiplier IS NULL
        ) OR
        -- MultipleOfMinWage override
        (
            protected_floor_cents IS NULL AND
            protected_min_wage_hourly_rate_cents IS NOT NULL AND
            protected_min_wage_hours IS NOT NULL AND
            protected_min_wage_multiplier IS NOT NULL
        )
    );
