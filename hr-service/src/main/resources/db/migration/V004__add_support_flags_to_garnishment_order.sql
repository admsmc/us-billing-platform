ALTER TABLE garnishment_order
    ADD COLUMN supports_other_dependents BOOLEAN;

ALTER TABLE garnishment_order
    ADD COLUMN arrears_at_least_12_weeks BOOLEAN;
