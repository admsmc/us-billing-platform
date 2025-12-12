-- Optional per-order overrides for formula/protected earnings.
--
-- This allows the authoritative garnishment_order lifecycle model to carry
-- order-specific statutory parameters (e.g., a fixed amount, a percent, or a
-- levy exemption table selection) instead of always deriving behavior solely
-- from (type, issuing jurisdiction).

ALTER TABLE garnishment_order
    ADD COLUMN formula_json CLOB;

ALTER TABLE garnishment_order
    ADD COLUMN protected_earnings_rule_json CLOB;
