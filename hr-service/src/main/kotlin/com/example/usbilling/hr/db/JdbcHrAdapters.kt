package com.example.usbilling.hr.db

import com.example.usbilling.hr.api.EmployeeSnapshotProvider
import com.example.usbilling.hr.api.PayPeriodProvider
import com.example.usbilling.payroll.engine.pub15t.W4Version
import com.example.usbilling.payroll.model.*
import com.example.usbilling.shared.EmployeeId
import com.example.usbilling.shared.EmployerId
import com.example.usbilling.shared.Money
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.LocalDate

@Repository
class JdbcEmployeeSnapshotProvider(
    private val jdbcTemplate: JdbcTemplate,
) : EmployeeSnapshotProvider {

    override fun getEmployeeSnapshot(employerId: EmployerId, employeeId: EmployeeId, asOfDate: LocalDate): EmployeeSnapshot? {
        val sql =
            """
            SELECT p.*, c.compensation_type, c.annual_salary_cents, c.hourly_rate_cents, c.pay_frequency
            FROM employee_profile_effective p
            LEFT JOIN employment_compensation c
              ON c.employer_id = p.employer_id
             AND c.employee_id = p.employee_id
             AND c.effective_from <= ?
             AND c.effective_to > ?
             AND c.system_from <= CURRENT_TIMESTAMP
             AND c.system_to > CURRENT_TIMESTAMP
            WHERE p.employer_id = ?
              AND p.employee_id = ?
              AND p.effective_from <= ?
              AND p.effective_to > ?
              AND p.system_from <= CURRENT_TIMESTAMP
              AND p.system_to > CURRENT_TIMESTAMP
            ORDER BY p.effective_from DESC, p.system_from DESC, c.effective_from DESC, c.system_from DESC
            FETCH FIRST 1 ROW ONLY
            """.trimIndent()

        val rows = jdbcTemplate.query(
            sql,
            EmployeeWithCompRowMapper,
            asOfDate,
            asOfDate,
            employerId.value,
            employeeId.value,
            asOfDate,
            asOfDate,
        )
        val row = rows.firstOrNull() ?: return null

        val filingStatus = FilingStatus.valueOf(row.filingStatus)
        val employmentType = EmploymentType.valueOf(row.employmentType)
        val flsaExemptStatus = FlsaExemptStatus.valueOf(row.flsaExemptStatus)

        val baseCompensation: BaseCompensation = when (row.compensationType) {
            "SALARIED" -> BaseCompensation.Salaried(
                annualSalary = Money(
                    requireNotNull(row.annualSalaryCents) {
                        "SALARIED compensation requires annual_salary_cents"
                    },
                ),
                frequency = PayFrequency.valueOf(row.payFrequency!!),
            )
            "HOURLY" -> BaseCompensation.Hourly(
                hourlyRate = Money(
                    requireNotNull(row.hourlyRateCents) {
                        "HOURLY compensation requires hourly_rate_cents"
                    },
                ),
            )
            else -> error("Unknown compensation_type='${row.compensationType}' for employee ${row.employerId}/${row.employeeId}")
        }

        return EmployeeSnapshot(
            employerId = EmployerId(row.employerId),
            employeeId = EmployeeId(row.employeeId),
            homeState = row.homeState,
            workState = row.workState,
            filingStatus = filingStatus,
            employmentType = employmentType,
            baseCompensation = baseCompensation,
            hireDate = row.hireDate,
            terminationDate = row.terminationDate,
            additionalWithholdingPerPeriod = row.additionalWithholdingCents?.let { Money(it) },
            dependents = row.dependents,
            federalWithholdingExempt = row.federalWithholdingExempt,
            isNonresidentAlien = row.isNonresidentAlien,
            w4AnnualCreditAmount = row.w4AnnualCreditCents?.let { Money(it) },
            w4OtherIncomeAnnual = row.w4OtherIncomeCents?.let { Money(it) },
            w4DeductionsAnnual = row.w4DeductionsCents?.let { Money(it) },
            w4Version = row.w4Version?.let { W4Version.valueOf(it) },
            legacyAllowances = row.legacyAllowances,
            legacyAdditionalWithholdingPerPeriod = row.legacyAdditionalWithholdingCents?.let { Money(it) },
            legacyMaritalStatus = row.legacyMaritalStatus,
            w4EffectiveDate = row.w4EffectiveDate,
            w4Step2MultipleJobs = row.w4Step2MultipleJobs,
            ficaExempt = row.ficaExempt,
            flsaEnterpriseCovered = row.flsaEnterpriseCovered,
            flsaExemptStatus = flsaExemptStatus,
            isTippedEmployee = row.isTippedEmployee,
            workCity = row.workCity,
        )
    }

    private data class EmployeeWithCompRow(
        val employerId: String,
        val employeeId: String,
        val homeState: String,
        val workState: String,
        val workCity: String?,
        val filingStatus: String,
        val employmentType: String,
        val hireDate: LocalDate?,
        val terminationDate: LocalDate?,
        val dependents: Int?,
        val federalWithholdingExempt: Boolean,
        val isNonresidentAlien: Boolean,
        val w4AnnualCreditCents: Long?,
        val w4OtherIncomeCents: Long?,
        val w4DeductionsCents: Long?,
        val w4Step2MultipleJobs: Boolean,
        val w4Version: String?,
        val legacyAllowances: Int?,
        val legacyAdditionalWithholdingCents: Long?,
        val legacyMaritalStatus: String?,
        val w4EffectiveDate: LocalDate?,
        val additionalWithholdingCents: Long?,
        val ficaExempt: Boolean,
        val flsaEnterpriseCovered: Boolean,
        val flsaExemptStatus: String,
        val isTippedEmployee: Boolean,
        val compensationType: String?,
        val annualSalaryCents: Long?,
        val hourlyRateCents: Long?,
        val payFrequency: String?,
    )

    private object EmployeeWithCompRowMapper : RowMapper<EmployeeWithCompRow> {
        override fun mapRow(rs: ResultSet, rowNum: Int): EmployeeWithCompRow = EmployeeWithCompRow(
            employerId = rs.getString("employer_id"),
            employeeId = rs.getString("employee_id"),
            homeState = rs.getString("home_state"),
            workState = rs.getString("work_state"),
            workCity = rs.getString("work_city"),
            filingStatus = rs.getString("filing_status"),
            employmentType = rs.getString("employment_type"),
            hireDate = rs.getDate("hire_date")?.toLocalDate(),
            terminationDate = rs.getDate("termination_date")?.toLocalDate(),
            dependents = rs.getObject("dependents")?.let { rs.getInt("dependents") },
            federalWithholdingExempt = rs.getBoolean("federal_withholding_exempt"),
            isNonresidentAlien = rs.getBoolean("is_nonresident_alien"),
            w4AnnualCreditCents = rs.getObject("w4_annual_credit_cents")?.let { rs.getLong("w4_annual_credit_cents") },
            w4OtherIncomeCents = rs.getObject("w4_other_income_cents")?.let { rs.getLong("w4_other_income_cents") },
            w4DeductionsCents = rs.getObject("w4_deductions_cents")?.let { rs.getLong("w4_deductions_cents") },
            w4Step2MultipleJobs = rs.getBoolean("w4_step2_multiple_jobs"),
            w4Version = rs.getString("w4_version"),
            legacyAllowances = rs.getObject("legacy_allowances")?.let { rs.getInt("legacy_allowances") },
            legacyAdditionalWithholdingCents = rs.getObject("legacy_additional_withholding_cents")?.let { rs.getLong("legacy_additional_withholding_cents") },
            legacyMaritalStatus = rs.getString("legacy_marital_status"),
            w4EffectiveDate = rs.getDate("w4_effective_date")?.toLocalDate(),
            additionalWithholdingCents = rs.getObject("additional_withholding_cents")?.let { rs.getLong("additional_withholding_cents") },
            ficaExempt = rs.getBoolean("fica_exempt"),
            flsaEnterpriseCovered = rs.getBoolean("flsa_enterprise_covered"),
            flsaExemptStatus = rs.getString("flsa_exempt_status"),
            isTippedEmployee = rs.getBoolean("is_tipped_employee"),
            compensationType = rs.getString("compensation_type"),
            annualSalaryCents = rs.getObject("annual_salary_cents")?.let { rs.getLong("annual_salary_cents") },
            hourlyRateCents = rs.getObject("hourly_rate_cents")?.let { rs.getLong("hourly_rate_cents") },
            payFrequency = rs.getString("pay_frequency"),
        )
    }
}

@Repository
class JdbcPayPeriodProvider(
    private val jdbcTemplate: JdbcTemplate,
) : PayPeriodProvider {

    override fun getPayPeriod(employerId: EmployerId, payPeriodId: String): PayPeriod? {
        val sql =
            """
            SELECT *
            FROM pay_period
            WHERE employer_id = ?
              AND id = ?
            """.trimIndent()

        val rows = jdbcTemplate.query(sql, PayPeriodRowMapper, employerId.value, payPeriodId)
        return rows.firstOrNull()?.toDomain()
    }

    override fun findPayPeriodByCheckDate(employerId: EmployerId, checkDate: LocalDate): PayPeriod? {
        val sql =
            """
            SELECT *
            FROM pay_period
            WHERE employer_id = ?
              AND check_date = ?
            ORDER BY id
            FETCH FIRST 1 ROW ONLY
            """.trimIndent()

        val rows = jdbcTemplate.query(sql, PayPeriodRowMapper, employerId.value, checkDate)
        return rows.firstOrNull()?.toDomain()
    }

    private data class PayPeriodRow(
        val employerId: String,
        val id: String,
        val startDate: LocalDate,
        val endDate: LocalDate,
        val checkDate: LocalDate,
        val frequency: String,
        val sequenceInYear: Int?,
    ) {
        fun toDomain(): PayPeriod = PayPeriod(
            id = id,
            employerId = EmployerId(employerId),
            dateRange = LocalDateRange(startInclusive = startDate, endInclusive = endDate),
            checkDate = checkDate,
            frequency = PayFrequency.valueOf(frequency),
            sequenceInYear = sequenceInYear,
        )
    }

    private object PayPeriodRowMapper : RowMapper<PayPeriodRow> {
        override fun mapRow(rs: ResultSet, rowNum: Int): PayPeriodRow = PayPeriodRow(
            employerId = rs.getString("employer_id"),
            id = rs.getString("id"),
            startDate = rs.getDate("start_date").toLocalDate(),
            endDate = rs.getDate("end_date").toLocalDate(),
            checkDate = rs.getDate("check_date").toLocalDate(),
            frequency = rs.getString("frequency"),
            sequenceInYear = rs.getObject("sequence_in_year")?.let { rs.getInt("sequence_in_year") },
        )
    }
}
