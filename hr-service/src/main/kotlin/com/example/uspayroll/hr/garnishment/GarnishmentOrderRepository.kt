package com.example.uspayroll.hr.garnishment

import com.example.uspayroll.payroll.model.TaxJurisdiction
import com.example.uspayroll.payroll.model.TaxJurisdictionType
import com.example.uspayroll.payroll.model.garnishment.GarnishmentFormula
import com.example.uspayroll.payroll.model.garnishment.GarnishmentType
import com.example.uspayroll.payroll.model.garnishment.ProtectedEarningsRule
import com.example.uspayroll.shared.EmployeeId
import com.example.uspayroll.shared.EmployerId
import com.example.uspayroll.shared.Money
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.LocalDate

/**
 * HR-side representation of a persisted garnishment order. This is the
 * authoritative lifecycle model for per-employee orders; rule config provides
 * statutory behavior such as formulas and protected earnings.
 */
data class GarnishmentOrderRow(
    val employerId: EmployerId,
    val employeeId: EmployeeId,
    val orderId: String,
    val type: GarnishmentType,
    val issuingJurisdiction: TaxJurisdiction?,
    val caseNumber: String?,
    val status: OrderStatus,
    val servedDate: LocalDate?,
    val endDate: LocalDate?,
    val priorityClass: Int,
    val sequenceWithinClass: Int,
    val initialArrears: Money?,
    val currentArrears: Money?,
    val supportsOtherDependents: Boolean?,
    val arrearsAtLeast12Weeks: Boolean?,
    /** Optional per-order override for statutory formula (JSON-encoded GarnishmentFormula). */
    val formulaOverride: GarnishmentFormula? = null,
    /** Optional per-order override for protected earnings rule (JSON-encoded ProtectedEarningsRule). */
    val protectedEarningsRuleOverride: ProtectedEarningsRule? = null,
)

enum class OrderStatus {
    ACTIVE,
    SUSPENDED,
    COMPLETED,
}

interface GarnishmentOrderRepository {

    fun findActiveOrdersForEmployee(employerId: EmployerId, employeeId: EmployeeId, asOf: LocalDate): List<GarnishmentOrderRow>

    /**
     * True if the employee has any persisted garnishment orders in HR (active or not).
     * Used to distinguish demo fallback behavior from real but currently-inactive orders.
     */
    fun hasAnyOrdersForEmployee(employerId: EmployerId, employeeId: EmployeeId): Boolean
}

@Repository
class JdbcGarnishmentOrderRepository(
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
) : GarnishmentOrderRepository {

    override fun findActiveOrdersForEmployee(employerId: EmployerId, employeeId: EmployeeId, asOf: LocalDate): List<GarnishmentOrderRow> {
        val sql =
            """
             SELECT employer_id,
                    employee_id,
                    order_id,
                    type,
                    issuing_jurisdiction_type,
                    issuing_jurisdiction_code,
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
                    -- Typed override columns
                    formula_type,
                    percent_of_disposable,
                    fixed_amount_cents,
                    protected_floor_cents,
                    protected_min_wage_hourly_rate_cents,
                    protected_min_wage_hours,
                    protected_min_wage_multiplier,
                    -- JSON escape hatch
                    formula_json,
                    protected_earnings_rule_json
             FROM garnishment_order
             WHERE employer_id = ?
               AND employee_id = ?
               AND status = 'ACTIVE'
               AND (served_date IS NULL OR served_date <= ?)
               AND (end_date IS NULL OR end_date >= ?)
            """.trimIndent()

        val rows = jdbcTemplate.query(
            sql,
            RowMapper { rs: ResultSet, _: Int ->
                mapRow(rs)
            },
            employerId.value,
            employeeId.value,
            asOf,
            asOf,
        )

        return rows
    }

    override fun hasAnyOrdersForEmployee(employerId: EmployerId, employeeId: EmployeeId): Boolean {
        val sql =
            """
            SELECT 1
            FROM garnishment_order
            WHERE employer_id = ?
              AND employee_id = ?
            LIMIT 1
            """.trimIndent()

        val rows = jdbcTemplate.queryForList(sql, Int::class.java, employerId.value, employeeId.value)
        return rows.isNotEmpty()
    }

    private fun mapRow(rs: ResultSet): GarnishmentOrderRow {
        val employerId = EmployerId(rs.getString("employer_id"))
        val employeeId = EmployeeId(rs.getString("employee_id"))
        val orderId = rs.getString("order_id")

        val type = GarnishmentType.valueOf(rs.getString("type"))

        val jurisdictionTypeRaw = rs.getString("issuing_jurisdiction_type")
        val jurisdictionCodeRaw = rs.getString("issuing_jurisdiction_code")
        val jurisdiction = if (!jurisdictionTypeRaw.isNullOrBlank() && !jurisdictionCodeRaw.isNullOrBlank()) {
            TaxJurisdiction(
                TaxJurisdictionType.valueOf(jurisdictionTypeRaw),
                jurisdictionCodeRaw,
            )
        } else {
            null
        }

        val status = OrderStatus.valueOf(rs.getString("status"))

        val servedDate = rs.getDate("served_date")?.toLocalDate()
        val endDate = rs.getDate("end_date")?.toLocalDate()

        val priorityClass = rs.getInt("priority_class")
        val sequenceWithinClass = rs.getInt("sequence_within_class")

        val initialArrearsCents = rs.getObject("initial_arrears_cents")?.let { rs.getLong("initial_arrears_cents") }
        val currentArrearsCents = rs.getObject("current_arrears_cents")?.let { rs.getLong("current_arrears_cents") }

        fun <T> parseJson(column: String, clazz: Class<T>): T? {
            val raw = rs.getString(column)
            if (raw.isNullOrBlank()) return null
            return objectMapper.readValue(raw, clazz)
        }

        // Prefer typed overrides for queryability; fall back to JSON escape hatch.
        val formulaTypeRaw = rs.getString("formula_type")?.trim()?.uppercase()
        val percentRaw = rs.getObject("percent_of_disposable")?.let { rs.getDouble("percent_of_disposable") }
        val fixedAmountRaw = rs.getObject("fixed_amount_cents")?.let { rs.getLong("fixed_amount_cents") }

        val typedFormulaOverride: GarnishmentFormula? = when (formulaTypeRaw) {
            null, "" -> null
            "PERCENT_OF_DISPOSABLE" -> {
                if (percentRaw == null) {
                    null
                } else {
                    GarnishmentFormula.PercentOfDisposable(
                        com.example.uspayroll.payroll.model.Percent(percentRaw),
                    )
                }
            }
            "FIXED_AMOUNT_PER_PERIOD" -> {
                if (fixedAmountRaw == null) {
                    null
                } else {
                    GarnishmentFormula.FixedAmountPerPeriod(
                        Money(fixedAmountRaw),
                    )
                }
            }
            "LESSER_OF_PERCENT_OR_AMOUNT" -> {
                if (percentRaw == null || fixedAmountRaw == null) {
                    null
                } else {
                    GarnishmentFormula.LesserOfPercentOrAmount(
                        percent = com.example.uspayroll.payroll.model.Percent(percentRaw),
                        amount = Money(fixedAmountRaw),
                    )
                }
            }
            // For levy-with-bands, the typical path is still rule-config-driven by
            // (type, jurisdiction). If a per-order override is needed, use JSON.
            "LEVY_WITH_BANDS" -> null
            else -> null
        }

        val floorRaw = rs.getObject("protected_floor_cents")?.let { rs.getLong("protected_floor_cents") }
        val minWageRateRaw = rs.getObject("protected_min_wage_hourly_rate_cents")?.let { rs.getLong("protected_min_wage_hourly_rate_cents") }
        val minWageHoursRaw = rs.getObject("protected_min_wage_hours")?.let { rs.getDouble("protected_min_wage_hours") }
        val minWageMultiplierRaw = rs.getObject("protected_min_wage_multiplier")?.let { rs.getDouble("protected_min_wage_multiplier") }

        val typedProtectedOverride: ProtectedEarningsRule? = when {
            floorRaw != null -> ProtectedEarningsRule.FixedFloor(Money(floorRaw))
            minWageRateRaw != null && minWageHoursRaw != null && minWageMultiplierRaw != null -> ProtectedEarningsRule.MultipleOfMinWage(
                hourlyRate = Money(minWageRateRaw),
                hours = minWageHoursRaw,
                multiplier = minWageMultiplierRaw,
            )
            else -> null
        }

        val formulaOverride = typedFormulaOverride ?: parseJson("formula_json", GarnishmentFormula::class.java)
        val protectedOverride = typedProtectedOverride ?: parseJson("protected_earnings_rule_json", ProtectedEarningsRule::class.java)

        return GarnishmentOrderRow(
            employerId = employerId,
            employeeId = employeeId,
            orderId = orderId,
            type = type,
            issuingJurisdiction = jurisdiction,
            caseNumber = rs.getString("case_number"),
            status = status,
            servedDate = servedDate,
            endDate = endDate,
            priorityClass = priorityClass,
            sequenceWithinClass = sequenceWithinClass,
            initialArrears = initialArrearsCents?.let { Money(it) },
            currentArrears = currentArrearsCents?.let { Money(it) },
            supportsOtherDependents = rs.getObject("supports_other_dependents") as Boolean?,
            arrearsAtLeast12Weeks = rs.getObject("arrears_at_least_12_weeks") as Boolean?,
            formulaOverride = formulaOverride,
            protectedEarningsRuleOverride = protectedOverride,
        )
    }
}
