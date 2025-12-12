package com.example.uspayroll.hr.garnishment

import org.flywaydb.core.Flyway
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import kotlin.test.assertTrue

class GarnishmentOrderOverrideDbAuditTest {

    private fun createJdbcTemplate(): JdbcTemplate {
        val ds = JdbcDataSource().apply {
            setURL("jdbc:h2:mem:hr_garnishment_override_audit;DB_CLOSE_DELAY=-1")
            user = "sa"
            password = ""
        }

        Flyway.configure()
            .dataSource(ds)
            .locations("classpath:db/migration")
            .load()
            .migrate()

        return JdbcTemplate(ds)
    }

    @Test
    fun `all garnishment_order override columns validate`() {
        val jdbc = createJdbcTemplate()

        val rows = jdbc.query(
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
        val summary = result.errors.joinToString(separator = "\n") { e ->
            " - orderId=${e.orderId}: ${e.message}"
        }

        assertTrue(result.isValid, "Expected all garnishment_order override columns to validate, but found errors:\n$summary")
    }
}
