-- Typed, queryable per-order override columns for garnishment calculations.
--
-- These columns allow admin/reporting queries like:
--   - "find all orders with a fixed amount"
--   - "find all orders with percent > X"
-- without parsing JSON. The existing JSON columns remain as an escape hatch
-- for complex/rare shapes (e.g., levy bands selection beyond jurisdiction).

ALTER TABLE garnishment_order
    ADD COLUMN formula_type VARCHAR(64);

ALTER TABLE garnishment_order
    ADD COLUMN percent_of_disposable DOUBLE;

ALTER TABLE garnishment_order
    ADD COLUMN fixed_amount_cents BIGINT;

-- Typed protected earnings overrides.
ALTER TABLE garnishment_order
    ADD COLUMN protected_floor_cents BIGINT;

ALTER TABLE garnishment_order
    ADD COLUMN protected_min_wage_hourly_rate_cents BIGINT;

ALTER TABLE garnishment_order
    ADD COLUMN protected_min_wage_hours DOUBLE;

ALTER TABLE garnishment_order
    ADD COLUMN protected_min_wage_multiplier DOUBLE;
