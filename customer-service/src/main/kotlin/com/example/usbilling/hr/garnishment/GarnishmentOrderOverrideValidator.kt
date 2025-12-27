package com.example.usbilling.hr.garnishment

/**
 * Validator for per-order garnishment override columns.
 *
 * This mirrors the intent of GarnishmentConfigValidator, but for persisted
 * rows in garnishment_order.
 *
 * The DB enforces many invariants via CHECK constraints (see V007 migration),
 * but this validator provides a friendly, testable set of error messages that
 * can be used by ingestion code, admin tooling, or offline audits.
 */
object GarnishmentOrderOverrideValidator {

    data class ValidationError(
        val orderId: String,
        val message: String,
    )

    data class ValidationResult(
        val errors: List<ValidationError>,
    ) {
        val isValid: Boolean get() = errors.isEmpty()
    }

    /**
     * Minimal view of the override-related columns in garnishment_order.
     *
     * NOTE: Values are stored as primitives so callers can populate this from
     * JDBC without bringing in JSON parsing.
     */
    data class OverrideColumns(
        val orderId: String,
        val formulaType: String?,
        val percentOfDisposable: Double?,
        val fixedAmountCents: Long?,
        val protectedFloorCents: Long?,
        val protectedMinWageHourlyRateCents: Long?,
        val protectedMinWageHours: Double?,
        val protectedMinWageMultiplier: Double?,
        val formulaJson: String?,
        val protectedEarningsRuleJson: String?,
    )

    fun validate(rows: List<OverrideColumns>): ValidationResult {
        val errors = mutableListOf<ValidationError>()
        rows.forEach { row ->
            validateRow(row, errors)
        }
        return ValidationResult(errors)
    }

    private fun validateRow(row: OverrideColumns, errors: MutableList<ValidationError>) {
        fun err(msg: String) {
            errors += ValidationError(orderId = row.orderId, message = msg)
        }

        row.percentOfDisposable?.let { p ->
            if (p < 0.0 || p > 1.0) err("percent_of_disposable must be within [0.0, 1.0] (found $p)")
        }
        row.fixedAmountCents?.let { a ->
            if (a < 0L) err("fixed_amount_cents must be >= 0 (found $a)")
        }
        row.protectedFloorCents?.let { a ->
            if (a < 0L) err("protected_floor_cents must be >= 0 (found $a)")
        }
        row.protectedMinWageHourlyRateCents?.let { a ->
            if (a < 0L) err("protected_min_wage_hourly_rate_cents must be >= 0 (found $a)")
        }
        row.protectedMinWageHours?.let { a ->
            if (a < 0.0) err("protected_min_wage_hours must be >= 0.0 (found $a)")
        }
        row.protectedMinWageMultiplier?.let { a ->
            if (a < 0.0) err("protected_min_wage_multiplier must be >= 0.0 (found $a)")
        }

        val ft = row.formulaType?.trim()?.uppercase()
        if (!ft.isNullOrBlank()) {
            when (ft) {
                "PERCENT_OF_DISPOSABLE" -> {
                    if (row.percentOfDisposable == null) err("formula_type=PERCENT_OF_DISPOSABLE requires percent_of_disposable")
                    if (row.fixedAmountCents != null) err("formula_type=PERCENT_OF_DISPOSABLE must not set fixed_amount_cents")
                }
                "FIXED_AMOUNT_PER_PERIOD" -> {
                    if (row.fixedAmountCents == null) err("formula_type=FIXED_AMOUNT_PER_PERIOD requires fixed_amount_cents")
                    if (row.percentOfDisposable != null) err("formula_type=FIXED_AMOUNT_PER_PERIOD must not set percent_of_disposable")
                }
                "LESSER_OF_PERCENT_OR_AMOUNT" -> {
                    if (row.fixedAmountCents == null || row.percentOfDisposable == null) {
                        err("formula_type=LESSER_OF_PERCENT_OR_AMOUNT requires both percent_of_disposable and fixed_amount_cents")
                    }
                }
                "LEVY_WITH_BANDS" -> {
                    // Typed columns don’t encode bands; rule config (by jurisdiction) or JSON override is expected.
                    if (row.percentOfDisposable != null || row.fixedAmountCents != null) {
                        err("formula_type=LEVY_WITH_BANDS must not set percent_of_disposable or fixed_amount_cents")
                    }
                }
                else -> err("Unknown formula_type '$ft'")
            }
        }

        // Protected earnings: either none, FixedFloor (floor only), or MultipleOfMinWage (all 3 fields).
        val hasFloor = row.protectedFloorCents != null
        val hasAnyMinWage = (row.protectedMinWageHourlyRateCents != null) ||
            (row.protectedMinWageHours != null) ||
            (row.protectedMinWageMultiplier != null)

        if (hasFloor && hasAnyMinWage) {
            err("protected earnings override must be either FixedFloor or MultipleOfMinWage, not both")
        }

        if (hasAnyMinWage) {
            if (row.protectedMinWageHourlyRateCents == null || row.protectedMinWageHours == null || row.protectedMinWageMultiplier == null) {
                err("MultipleOfMinWage override requires hourly_rate_cents, hours, and multiplier")
            }
        }

        // Optional: warn if both typed and JSON are populated (not invalid, but ambiguous).
        val hasTypedFormula = !ft.isNullOrBlank() || row.percentOfDisposable != null || row.fixedAmountCents != null
        val hasJsonFormula = !row.formulaJson.isNullOrBlank()
        if (hasTypedFormula && hasJsonFormula) {
            err("Both typed formula override columns and formula_json are populated; prefer only one")
        }

        val hasTypedProtected = hasFloor || hasAnyMinWage
        val hasJsonProtected = !row.protectedEarningsRuleJson.isNullOrBlank()
        if (hasTypedProtected && hasJsonProtected) {
            err("Both typed protected-earnings override columns and protected_earnings_rule_json are populated; prefer only one")
        }
    }
}
