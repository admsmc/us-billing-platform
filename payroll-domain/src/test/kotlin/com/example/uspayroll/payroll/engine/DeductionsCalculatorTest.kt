package com.example.uspayroll.payroll.engine

import com.example.uspayroll.payroll.model.*
import com.example.uspayroll.payroll.model.config.DeductionConfigRepository
import com.example.uspayroll.payroll.model.config.DeductionKind
import com.example.uspayroll.payroll.model.config.DeductionPlan
import com.example.uspayroll.shared.EmployeeId
import com.example.uspayroll.shared.EmployerId
import com.example.uspayroll.shared.Money
import com.example.uspayroll.shared.PayRunId
import com.example.uspayroll.shared.PaycheckId
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

private class TestDeductionConfigRepository : DeductionConfigRepository {
    override fun findPlansForEmployer(employerId: EmployerId): List<DeductionPlan> = listOf(
        DeductionPlan(
            id = "PLAN_VOLUNTARY",
            name = "Voluntary Post-Tax Deduction",
            kind = DeductionKind.POSTTAX_VOLUNTARY,
            employeeFlat = Money(100_00L), // $100
        ),
    )
}

class DeductionsCalculatorTest {

    @Test
    fun `post tax voluntary deduction reduces net pay`() {
        val employerId = EmployerId("emp-1")
        val employeeId = EmployeeId("ee-4")
        val period = PayPeriod(
            id = "2025-01-BW3",
            employerId = employerId,
            dateRange = LocalDateRange(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 14)),
            checkDate = LocalDate.of(2025, 1, 15),
            frequency = PayFrequency.BIWEEKLY,
        )
        val snapshot = EmployeeSnapshot(
            employerId = employerId,
            employeeId = employeeId,
            homeState = "CA",
            workState = "CA",
            filingStatus = FilingStatus.SINGLE,
            baseCompensation = BaseCompensation.Salaried(
                annualSalary = Money(260_000_00L), // $260,000
                frequency = period.frequency,
            ),
        )
        val input = PaycheckInput(
            paycheckId = PaycheckId("chk-4"),
            payRunId = PayRunId("run-3"),
            employerId = employerId,
            employeeId = employeeId,
            period = period,
            employeeSnapshot = snapshot,
            timeSlice = TimeSlice(
                period = period,
                regularHours = 0.0,
                overtimeHours = 0.0,
            ),
            taxContext = TaxContext(),
            priorYtd = YtdSnapshot(year = 2025),
        )

        val result = PayrollEngine.calculatePaycheck(
            input = input,
            earningConfig = null,
            deductionConfig = TestDeductionConfigRepository(),
        )

        // Gross is still 10,000 (as in the salaried tests)
        assertEquals(10_000_00L, result.gross.amount)

        // Net should be gross minus 100
        assertEquals(9_900_00L, result.net.amount)

        // There should be one post-tax deduction line for $100
        val deduction = result.deductions.single()
        assertEquals(Money(100_00L), deduction.amount)

        // YTD should include the voluntary deduction under its plan code
        val ytdVoluntary = result.ytdAfter.deductionsByCode[DeductionCode("PLAN_VOLUNTARY")]
        assertEquals(100_00L, ytdVoluntary?.amount)
    }

    private class EmptyDeductionConfigRepository : DeductionConfigRepository {
        override fun findPlansForEmployer(employerId: EmployerId): List<DeductionPlan> = emptyList()
    }

    @Test
    fun `no voluntary plan means no deductions and net equals gross`() {
        val employerId = EmployerId("emp-2")
        val employeeId = EmployeeId("ee-5")
        val period = PayPeriod(
            id = "2025-01-BW4",
            employerId = employerId,
            dateRange = LocalDateRange(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 14)),
            checkDate = LocalDate.of(2025, 1, 15),
            frequency = PayFrequency.BIWEEKLY,
        )
        val snapshot = EmployeeSnapshot(
            employerId = employerId,
            employeeId = employeeId,
            homeState = "CA",
            workState = "CA",
            filingStatus = FilingStatus.SINGLE,
            baseCompensation = BaseCompensation.Salaried(
                annualSalary = Money(260_000_00L), // $260,000
                frequency = period.frequency,
            ),
        )
        val input = PaycheckInput(
            paycheckId = PaycheckId("chk-5"),
            payRunId = PayRunId("run-4"),
            employerId = employerId,
            employeeId = employeeId,
            period = period,
            employeeSnapshot = snapshot,
            timeSlice = TimeSlice(
                period = period,
                regularHours = 0.0,
                overtimeHours = 0.0,
            ),
            taxContext = TaxContext(),
            priorYtd = YtdSnapshot(year = 2025),
        )

        val result = PayrollEngine.calculatePaycheck(
            input = input,
            earningConfig = null,
            deductionConfig = EmptyDeductionConfigRepository(),
        )

        assertEquals(10_000_00L, result.gross.amount)
        assertEquals(result.gross.amount, result.net.amount)
        assertEquals(0, result.deductions.size)
        assertEquals(0, result.ytdAfter.deductionsByCode.size)
    }
}
