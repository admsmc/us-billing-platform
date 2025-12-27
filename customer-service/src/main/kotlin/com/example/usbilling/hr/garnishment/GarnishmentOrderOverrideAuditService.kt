package com.example.usbilling.hr.garnishment

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

@Service
class GarnishmentOrderOverrideAuditService(
    private val jdbcTemplate: JdbcTemplate,
) {

    data class AuditReport(
        val rowsChecked: Int,
        val errors: List<GarnishmentOrderOverrideValidator.ValidationError>,
    ) {
        val isValid: Boolean get() = errors.isEmpty()
    }

    /**
     * Query all rows in garnishment_order and validate the override-related columns.
     */
    fun auditAllOrders(): AuditReport {
        val rows = jdbcTemplate.query(
            """
            SELECT order_id,
                   formula_type,
                   percent_of_disposable,
                   fixed_amount_cents,
                   protected_floor_cents,
                   protected_min_wage_hourly_rate_cents,
                   protected_min_wage_hours,
                   protected_min_wage_multiplier,
                   formula_json,
                   protected_earnings_rule_json
            FROM garnishment_order
            """.trimIndent(),
        ) { rs, _ ->
            GarnishmentOrderOverrideValidator.OverrideColumns(
                orderId = rs.getString("order_id"),
                formulaType = rs.getString("formula_type"),
                percentOfDisposable = rs.getObject("percent_of_disposable")?.let { rs.getDouble("percent_of_disposable") },
                fixedAmountCents = rs.getObject("fixed_amount_cents")?.let { rs.getLong("fixed_amount_cents") },
                protectedFloorCents = rs.getObject("protected_floor_cents")?.let { rs.getLong("protected_floor_cents") },
                protectedMinWageHourlyRateCents = rs.getObject("protected_min_wage_hourly_rate_cents")?.let { rs.getLong("protected_min_wage_hourly_rate_cents") },
                protectedMinWageHours = rs.getObject("protected_min_wage_hours")?.let { rs.getDouble("protected_min_wage_hours") },
                protectedMinWageMultiplier = rs.getObject("protected_min_wage_multiplier")?.let { rs.getDouble("protected_min_wage_multiplier") },
                formulaJson = rs.getString("formula_json"),
                protectedEarningsRuleJson = rs.getString("protected_earnings_rule_json"),
            )
        }

        val result = GarnishmentOrderOverrideValidator.validate(rows)
        return AuditReport(
            rowsChecked = rows.size,
            errors = result.errors,
        )
    }
}
