package com.example.usbilling.payroll.engine

import com.example.usbilling.payroll.model.*
import com.example.usbilling.shared.CustomerId
import com.example.usbilling.shared.UtilityId
import com.example.usbilling.shared.Money
import com.example.usbilling.shared.BillingCycleId
import com.example.usbilling.shared.BillId
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class AdditionalWithholdingTest {

    private fun baseInput(employerId: UtilityId, employeeId: CustomerId, withAdditional: Money? = null): PaycheckInput {
        val period = PayPeriod(
            id = "2025-01-BW-AW",
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
                annualSalary = Money(260_000_00L),
                frequency = period.frequency,
            ),
            additionalWithholdingPerPeriod = withAdditional,
        )
        return PaycheckInput(
            paycheckId = BillId("chk-aw"),
            payRunId = BillingCycleId("run-aw"),
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
    }

    @Test
    fun `flat tax rule includes per-employee additional withholding`() {
        val employerId = UtilityId("emp-aw-flat")
        val employeeId = CustomerId("ee-aw-flat")

        // Base input with $50 additional per period
        val input = baseInput(employerId, employeeId, withAdditional = Money(50_00L))

        // Flat 10% on Gross
        val ruleId = "AW_FLAT"
        val rule = TaxRule.FlatRateTax(
            id = ruleId,
            jurisdiction = TaxJurisdiction(TaxJurisdictionType.FEDERAL, "US"),
            basis = TaxBasis.Gross,
            rate = Percent(0.10),
        )

        val taxedInput = input.copy(
            taxContext = TaxContext(federal = listOf(rule)),
        )

        val result = calculatePaycheckDebug(taxedInput)

        // Biweekly gross from salaried: 260,000 / 26 = 10,000
        assertEquals(10_000_00L, result.gross.amount)

        val tax = result.employeeTaxes.single()
        // Rule tax: 10% of 10,000 = 1,000, plus 50 extra withholding = 1,050
        assertEquals(1_050_00L, tax.amount.amount)

        // Net = 10,000 - 1,050
        assertEquals(8_950_00L, result.net.amount)

        // Trace should include an AdditionalWithholdingApplied step
        val extraSteps = result.trace.steps.filterIsInstance<TraceStep.AdditionalWithholdingApplied>()
        assertEquals(1, extraSteps.size)
        assertEquals(50_00L, extraSteps.first().amount.amount)

        // YTD should include full 1,050 under the rule id (rule + extra withholding)
        val ytdTax = result.ytdAfter.employeeTaxesByRuleId[ruleId]
        assertEquals(1_050_00L, ytdTax?.amount)
    }

    @Test
    fun `bracketed tax rule includes per-employee additional withholding`() {
        val employerId = UtilityId("emp-aw-br")
        val employeeId = CustomerId("ee-aw-br")

        // Base input with $75 additional per period
        val input = baseInput(employerId, employeeId, withAdditional = Money(75_00L))

        // Single bracket at 10% on Gross (acts like flat for this test)
        val ruleId = "AW_BR"
        val rule = TaxRule.BracketedIncomeTax(
            id = ruleId,
            jurisdiction = TaxJurisdiction(TaxJurisdictionType.FEDERAL, "US"),
            basis = TaxBasis.Gross,
            brackets = listOf(
                TaxBracket(upTo = null, rate = Percent(0.10)),
            ),
            standardDeduction = null,
            additionalWithholding = null,
        )

        val taxedInput = input.copy(
            taxContext = TaxContext(federal = listOf(rule)),
        )

        val result = calculatePaycheckDebug(taxedInput)

        // Biweekly gross still 10,000
        assertEquals(10_000_00L, result.gross.amount)

        val tax = result.employeeTaxes.single()
        // Rule tax: 10% of 10,000 = 1,000, plus 75 extra withholding = 1,075
        assertEquals(1_075_00L, tax.amount.amount)

        // Net = 10,000 - 1,075
        assertEquals(8_925_00L, result.net.amount)

        // Trace should include an AdditionalWithholdingApplied step
        val extraSteps = result.trace.steps.filterIsInstance<TraceStep.AdditionalWithholdingApplied>()
        assertEquals(1, extraSteps.size)
        assertEquals(75_00L, extraSteps.first().amount.amount)

        // YTD should include full 1,075 under the rule id
        val ytdTax = result.ytdAfter.employeeTaxesByRuleId[ruleId]
        assertEquals(1_075_00L, ytdTax?.amount)
    }
}
