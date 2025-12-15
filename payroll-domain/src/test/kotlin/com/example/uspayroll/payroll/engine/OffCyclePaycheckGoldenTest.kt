package com.example.uspayroll.payroll.engine

import com.example.uspayroll.payroll.model.BaseCompensation
import com.example.uspayroll.payroll.model.EarningCode
import com.example.uspayroll.payroll.model.EarningInput
import com.example.uspayroll.payroll.model.EmployeeSnapshot
import com.example.uspayroll.payroll.model.FilingStatus
import com.example.uspayroll.payroll.model.LocalDateRange
import com.example.uspayroll.payroll.model.PayFrequency
import com.example.uspayroll.payroll.model.PayPeriod
import com.example.uspayroll.payroll.model.PaycheckInput
import com.example.uspayroll.payroll.model.Percent
import com.example.uspayroll.payroll.model.TaxBasis
import com.example.uspayroll.payroll.model.TaxContext
import com.example.uspayroll.payroll.model.TaxJurisdiction
import com.example.uspayroll.payroll.model.TaxJurisdictionType
import com.example.uspayroll.payroll.model.TaxRule
import com.example.uspayroll.payroll.model.TimeSlice
import com.example.uspayroll.payroll.model.TraceStep
import com.example.uspayroll.payroll.model.YtdSnapshot
import com.example.uspayroll.shared.EmployeeId
import com.example.uspayroll.shared.EmployerId
import com.example.uspayroll.shared.Money
import com.example.uspayroll.shared.PayRunId
import com.example.uspayroll.shared.PaycheckId
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Golden-style test for off-cycle pay runs:
 * - Base earnings are suppressed.
 * - Only explicitly-provided earnings are paid.
 * - Supplemental tax rules can target the SupplementalWages basis.
 */
class OffCyclePaycheckGoldenTest {

    @Test
    fun `off-cycle paycheck pays only supplemental earnings (no base salary)`() {
        val employerId = EmployerId("emp-offcycle")
        val employeeId = EmployeeId("ee-offcycle")

        val period = PayPeriod(
            id = "2025-OFF-CYCLE-01",
            employerId = employerId,
            dateRange = LocalDateRange(LocalDate.of(2025, 2, 1), LocalDate.of(2025, 2, 1)),
            checkDate = LocalDate.of(2025, 2, 2),
            frequency = PayFrequency.BIWEEKLY,
        )

        val snapshot = EmployeeSnapshot(
            employerId = employerId,
            employeeId = employeeId,
            homeState = "CA",
            workState = "CA",
            filingStatus = FilingStatus.SINGLE,
            baseCompensation = BaseCompensation.Salaried(
                annualSalary = Money(260_000_00L),
                frequency = period.frequency,
            ),
        )

        val bonus = EarningInput(
            code = EarningCode("BONUS"),
            units = 1.0,
            rate = null,
            amount = Money(1_000_00L),
        )

        val ruleId = "SUPP_10_PCT"
        val supplementalTax = TaxRule.FlatRateTax(
            id = ruleId,
            jurisdiction = TaxJurisdiction(TaxJurisdictionType.FEDERAL, "US"),
            basis = TaxBasis.SupplementalWages,
            rate = Percent(0.10),
        )

        val input = PaycheckInput(
            paycheckId = PaycheckId("chk-offcycle"),
            payRunId = PayRunId("run-offcycle"),
            employerId = employerId,
            employeeId = employeeId,
            period = period,
            employeeSnapshot = snapshot,
            timeSlice = TimeSlice(
                period = period,
                regularHours = 0.0,
                overtimeHours = 0.0,
                otherEarnings = listOf(bonus),
                includeBaseEarnings = false,
            ),
            taxContext = TaxContext(federal = listOf(supplementalTax)),
            priorYtd = YtdSnapshot(year = 2025),
        )

        val result = calculatePaycheckDebug(input)

        // Only the explicit bonus is paid.
        assertEquals(1_000_00L, result.gross.amount)
        assertEquals(900_00L, result.net.amount)

        assertEquals(1, result.earnings.size)
        assertEquals("BONUS", result.earnings.single().code.value)

        assertTrue(result.earnings.none { it.code.value == "BASE" })

        // Supplemental basis should be 1,000.
        val suppBasis = result.trace.steps.filterIsInstance<TraceStep.BasisComputed>()
            .first { it.basis == TaxBasis.SupplementalWages }
        assertEquals(1_000_00L, suppBasis.result.amount)

        val tax = result.employeeTaxes.single { it.ruleId == ruleId }
        assertEquals(100_00L, tax.amount.amount)
    }
}
