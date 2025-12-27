package com.example.usbilling.payroll.engine

import com.example.usbilling.payroll.model.BaseCompensation
import com.example.usbilling.payroll.model.EarningCode
import com.example.usbilling.payroll.model.EarningInput
import com.example.usbilling.payroll.model.EmployeeSnapshot
import com.example.usbilling.payroll.model.FilingStatus
import com.example.usbilling.payroll.model.LocalDateRange
import com.example.usbilling.payroll.model.PayFrequency
import com.example.usbilling.payroll.model.PayPeriod
import com.example.usbilling.payroll.model.PaycheckInput
import com.example.usbilling.payroll.model.Percent
import com.example.usbilling.payroll.model.TaxBasis
import com.example.usbilling.payroll.model.TaxContext
import com.example.usbilling.payroll.model.TaxJurisdiction
import com.example.usbilling.payroll.model.TaxJurisdictionType
import com.example.usbilling.payroll.model.TaxRule
import com.example.usbilling.payroll.model.TimeSlice
import com.example.usbilling.payroll.model.TraceStep
import com.example.usbilling.payroll.model.YtdSnapshot
import com.example.usbilling.shared.EmployeeId
import com.example.usbilling.shared.EmployerId
import com.example.usbilling.shared.Money
import com.example.usbilling.shared.PayRunId
import com.example.usbilling.shared.PaycheckId
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
