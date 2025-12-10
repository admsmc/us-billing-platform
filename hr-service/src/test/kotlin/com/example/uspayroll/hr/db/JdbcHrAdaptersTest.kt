package com.example.uspayroll.hr.db

import com.example.uspayroll.hr.api.EmployeeSnapshotProvider
import com.example.uspayroll.hr.api.PayPeriodProvider
import com.example.uspayroll.payroll.model.*
import com.example.uspayroll.shared.EmployeeId
import com.example.uspayroll.shared.EmployerId
import com.example.uspayroll.shared.Money
import org.flywaydb.core.Flyway
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * H2-backed tests for the JDBC HR adapters. These tests seed the HR schema
 * directly and verify that [JdbcEmployeeSnapshotProvider] and
 * [JdbcPayPeriodProvider] correctly map rows into domain models.
 */
class JdbcHrAdaptersTest {

    private fun createJdbcTemplate(): JdbcTemplate {
        val ds = JdbcDataSource().apply {
            setURL("jdbc:h2:mem:hr_adapters;DB_CLOSE_DELAY=-1")
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
    fun `JdbcEmployeeSnapshotProvider returns expected EmployeeSnapshot`() {
        val jdbcTemplate = createJdbcTemplate()
        val employerId = EmployerId("EMP-HR-JDBC")
        val employeeId = EmployeeId("EE-JDBC-1")
        val asOf = LocalDate.of(2025, 1, 15)

        // Seed employee and compensation rows.
        jdbcTemplate.update(
            """
            INSERT INTO employee (
              employer_id, employee_id, home_state, work_state, work_city,
              filing_status, employment_type,
              hire_date, termination_date,
              dependents,
              federal_withholding_exempt, is_nonresident_alien,
              w4_annual_credit_cents, w4_other_income_cents, w4_deductions_cents,
              w4_step2_multiple_jobs,
              additional_withholding_cents,
              fica_exempt, flsa_enterprise_covered, flsa_exempt_status, is_tipped_employee
            ) VALUES (?, ?, 'CA', 'CA', 'San Francisco', 'SINGLE', 'REGULAR', ?, NULL, 2,
                      FALSE, FALSE,
                      100000, 200000, 300000,
                      FALSE,
                      5000,
                      FALSE, TRUE, 'NON_EXEMPT', FALSE)
            """.trimIndent(),
            employerId.value,
            employeeId.value,
            LocalDate.of(2024, 6, 1),
        )

        jdbcTemplate.update(
            """
            INSERT INTO employment_compensation (
              employer_id, employee_id,
              effective_from, effective_to,
              compensation_type,
              annual_salary_cents, hourly_rate_cents, pay_frequency
            ) VALUES (?, ?, ?, ?, 'SALARIED', 52_00000, NULL, 'BIWEEKLY')
            """.trimIndent(),
            employerId.value,
            employeeId.value,
            LocalDate.of(2024, 6, 1),
            LocalDate.of(9999, 12, 31),
        )

        val provider: EmployeeSnapshotProvider = JdbcEmployeeSnapshotProvider(jdbcTemplate)

        val snapshot = provider.getEmployeeSnapshot(employerId, employeeId, asOf)
        assertNotNull(snapshot, "Expected employee snapshot from JDBC provider")
        assertEquals(employerId, snapshot.employerId)
        assertEquals(employeeId, snapshot.employeeId)
        assertEquals("CA", snapshot.homeState)
        assertEquals("CA", snapshot.workState)
        assertEquals(FilingStatus.SINGLE, snapshot.filingStatus)
        assertEquals(EmploymentType.REGULAR, snapshot.employmentType)
        assertEquals(2, snapshot.dependents)
        assertEquals(false, snapshot.federalWithholdingExempt)
        assertEquals(false, snapshot.isNonresidentAlien)
        assertEquals(100_000L, snapshot.w4AnnualCreditAmount?.amount)
        assertEquals(200_000L, snapshot.w4OtherIncomeAnnual?.amount)
        assertEquals(300_000L, snapshot.w4DeductionsAnnual?.amount)
        assertEquals(5_000L, snapshot.additionalWithholdingPerPeriod?.amount)
        assertEquals(false, snapshot.ficaExempt)
        assertEquals(true, snapshot.flsaEnterpriseCovered)
        assertEquals(FlsaExemptStatus.NON_EXEMPT, snapshot.flsaExemptStatus)
        assertEquals(false, snapshot.isTippedEmployee)

        val base = snapshot.baseCompensation as BaseCompensation.Salaried
        assertEquals(52_000_00L, base.annualSalary.amount)
        assertEquals(PayFrequency.BIWEEKLY, base.frequency)
    }

    @Test
    fun `JdbcPayPeriodProvider returns expected PayPeriod and lookup by check date`() {
        val jdbcTemplate = createJdbcTemplate()
        val employerId = EmployerId("EMP-HR-JDBC")
        val payPeriodId = "2025-01-BW1"
        val start = LocalDate.of(2025, 1, 1)
        val end = LocalDate.of(2025, 1, 14)
        val checkDate = LocalDate.of(2025, 1, 15)

        jdbcTemplate.update(
            """
            INSERT INTO pay_period (
              employer_id, id,
              start_date, end_date, check_date,
              frequency, sequence_in_year
            ) VALUES (?, ?, ?, ?, ?, 'BIWEEKLY', 1)
            """.trimIndent(),
            employerId.value,
            payPeriodId,
            start,
            end,
            checkDate,
        )

        val provider: PayPeriodProvider = JdbcPayPeriodProvider(jdbcTemplate)

        val fromId = provider.getPayPeriod(employerId, payPeriodId)
        assertNotNull(fromId, "Expected PayPeriod by id")
        assertEquals(payPeriodId, fromId.id)
        assertEquals(start, fromId.dateRange.startInclusive)
        assertEquals(end, fromId.dateRange.endInclusive)
        assertEquals(checkDate, fromId.checkDate)
        assertEquals(PayFrequency.BIWEEKLY, fromId.frequency)

        val fromCheckDate = provider.findPayPeriodByCheckDate(employerId, checkDate)
        assertNotNull(fromCheckDate, "Expected PayPeriod by check date")
        assertEquals(payPeriodId, fromCheckDate.id)
    }
}