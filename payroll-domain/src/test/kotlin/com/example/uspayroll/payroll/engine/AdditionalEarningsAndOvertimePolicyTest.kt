package com.example.uspayroll.payroll.engine

import com.example.uspayroll.payroll.model.*
import com.example.uspayroll.payroll.model.config.EarningConfigRepository
import com.example.uspayroll.payroll.model.config.EarningDefinition
import com.example.uspayroll.shared.EmployeeId
import com.example.uspayroll.shared.EmployerId
import com.example.uspayroll.shared.Money
import com.example.uspayroll.shared.PayRunId
import com.example.uspayroll.shared.PaycheckId
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

private class TestEarningConfigRepository : EarningConfigRepository {
    override fun findByEmployerAndCode(employerId: EmployerId, code: EarningCode): EarningDefinition? = when (code.value) {
        "HOURLY" -> EarningDefinition(
            code = code,
            displayName = "Hourly Wages",
            category = EarningCategory.REGULAR,
            defaultRate = Money(50_00L),
            overtimeMultiplier = 2.0, // for overtime policy test
        )
        "BONUS" -> EarningDefinition(
            code = code,
            displayName = "Bonus",
            category = EarningCategory.BONUS,
        )
        else -> null
    }
}

class AdditionalEarningsAndOvertimePolicyTest {

    @Test
    fun `hourly with overtime uses configured multiplier (generic)`() {
        val employerId = EmployerId("emp-ot")
        val employeeId = EmployeeId("ee-ot2")
        val period = PayPeriod(
            id = "2025-01-W-OT2",
            employerId = employerId,
            dateRange = LocalDateRange(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 7)),
            checkDate = LocalDate.of(2025, 1, 8),
            frequency = PayFrequency.WEEKLY,
        )
        val snapshot = EmployeeSnapshot(
            employerId = employerId,
            employeeId = employeeId,
            homeState = "CA",
            workState = "CA",
            filingStatus = FilingStatus.SINGLE,
            baseCompensation = BaseCompensation.Hourly(hourlyRate = Money(50_00L)),
        )
        val input = PaycheckInput(
            paycheckId = PaycheckId("chk-ot2"),
            payRunId = PayRunId("run-ot2"),
            employerId = employerId,
            employeeId = employeeId,
            period = period,
            employeeSnapshot = snapshot,
            timeSlice = TimeSlice(
                period = period,
                regularHours = 40.0,
                overtimeHours = 5.0,
            ),
            taxContext = TaxContext(),
            priorYtd = YtdSnapshot(year = 2025),
        )

        val result = calculatePaycheckDebug(
            input = input,
            earningConfig = TestEarningConfigRepository(),
            deductionConfig = null,
        )

        // Regular: 40 * 50 = 2,000
        // Overtime: 5 * (50 * 2.0) = 500
        assertEquals(2_500_00L, result.gross.amount)
    }

    @Test
    fun `CA-style daily overtime shaping yields correct overtime dollars`() {
        val employerId = EmployerId("emp-ot-ca")
        val employeeId = EmployeeId("ee-ot-ca")
        val period = PayPeriod(
            id = "2025-01-W-OT-CA",
            employerId = employerId,
            dateRange = LocalDateRange(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 7)),
            checkDate = LocalDate.of(2025, 1, 8),
            frequency = PayFrequency.WEEKLY,
        )
        val snapshot = EmployeeSnapshot(
            employerId = employerId,
            employeeId = employeeId,
            homeState = "CA",
            workState = "CA",
            filingStatus = FilingStatus.SINGLE,
            baseCompensation = BaseCompensation.Hourly(hourlyRate = Money(40_00L)),
        )

        // Model a CA employee working 5 days of 10 hours each:
        // - 8 * 5 = 40 regular hours
        // - 2 * 5 = 10 daily overtime hours
        // Upstream timesheet logic would derive these counts from the 8-hour daily
        // threshold configured in labor standards; here we assert the engine
        // correctly prices the 10 overtime hours at the configured multiplier.
        val input = PaycheckInput(
            paycheckId = PaycheckId("chk-ot-ca"),
            payRunId = PayRunId("run-ot-ca"),
            employerId = employerId,
            employeeId = employeeId,
            period = period,
            employeeSnapshot = snapshot,
            timeSlice = TimeSlice(
                period = period,
                regularHours = 40.0,
                overtimeHours = 10.0,
            ),
            taxContext = TaxContext(),
            priorYtd = YtdSnapshot(year = 2025),
        )

        val result = calculatePaycheckDebug(
            input = input,
            earningConfig = TestEarningConfigRepository(),
            deductionConfig = null,
        )

        // Regular: 40 * 40 = 1,600
        // Overtime: 10 * (40 * 2.0) = 800
        assertEquals(2_400_00L, result.gross.amount)
    }

    @Test
    fun `other earnings are converted into earning lines`() {
        val employerId = EmployerId("emp-bonus")
        val employeeId = EmployeeId("ee-bonus")
        val period = PayPeriod(
            id = "2025-01-W-BONUS",
            employerId = employerId,
            dateRange = LocalDateRange(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 7)),
            checkDate = LocalDate.of(2025, 1, 8),
            frequency = PayFrequency.WEEKLY,
        )
        val snapshot = EmployeeSnapshot(
            employerId = employerId,
            employeeId = employeeId,
            homeState = "CA",
            workState = "CA",
            filingStatus = FilingStatus.SINGLE,
            baseCompensation = BaseCompensation.Hourly(hourlyRate = Money(50_00L)),
        )

        val bonusInput = EarningInput(
            code = EarningCode("BONUS"),
            units = 1.0,
            rate = null,
            amount = Money(1_000_00L),
        )

        val input = PaycheckInput(
            paycheckId = PaycheckId("chk-bonus"),
            payRunId = PayRunId("run-bonus"),
            employerId = employerId,
            employeeId = employeeId,
            period = period,
            employeeSnapshot = snapshot,
            timeSlice = TimeSlice(
                period = period,
                regularHours = 40.0,
                overtimeHours = 0.0,
                otherEarnings = listOf(bonusInput),
            ),
            taxContext = TaxContext(),
            priorYtd = YtdSnapshot(year = 2025),
        )

        val result = calculatePaycheckDebug(
            input = input,
            earningConfig = TestEarningConfigRepository(),
            deductionConfig = null,
        )

        // Regular: 40 * 50 = 2,000; bonus: 1,000; total = 3,000
        assertEquals(3_000_00L, result.gross.amount)

        val bonusLine = result.earnings.first { it.code == EarningCode("BONUS") }
        assertEquals(Money(1_000_00L), bonusLine.amount)
        assertEquals(EarningCategory.BONUS, bonusLine.category)
    }
}
