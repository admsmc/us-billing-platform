package com.example.uspayroll.payroll.engine

import com.example.uspayroll.shared.EmployeeId
import com.example.uspayroll.shared.EmployerId
import com.example.uspayroll.shared.Money
import com.example.uspayroll.shared.PaycheckId
import com.example.uspayroll.shared.PayRunId
import com.example.uspayroll.payroll.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import java.time.LocalDate

class SupplementalTaxTest {

    private fun baseInputWithSupplemental(
        employerId: EmployerId,
        employeeId: EmployeeId,
    ): PaycheckInput {
        val period = PayPeriod(
            id = "2025-01-BW-SUPP",
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
            baseCompensation = BaseCompensation.Hourly(hourlyRate = Money(50_00L)),
        )

        // Regular wages: 40 * 50 = 2,000
        // Supplemental bonus: 1,000
        val bonusInput = EarningInput(
            code = EarningCode("BONUS_SUPP"),
            units = 1.0,
            rate = null,
            amount = Money(1_000_00L),
        )

        return PaycheckInput(
            paycheckId = PaycheckId("chk-supp"),
            payRunId = PayRunId("run-supp"),
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
    }

    @Test
    fun `flat supplemental tax applies only to supplemental wages`() {
        val employerId = EmployerId("emp-supp")
        val employeeId = EmployeeId("ee-supp")

        val input = baseInputWithSupplemental(employerId, employeeId)

        // Flat 10% tax on SupplementalWages only
        val ruleId = "SUPP_FLAT"
        val rule = TaxRule.FlatRateTax(
            id = ruleId,
            jurisdiction = TaxJurisdiction(TaxJurisdictionType.FEDERAL, "US"),
            basis = TaxBasis.SupplementalWages,
            rate = Percent(0.10),
        )

        val taxedInput = input.copy(
            taxContext = TaxContext(federal = listOf(rule)),
        )

        val result = PayrollEngine.calculatePaycheck(taxedInput)

        // Gross: 2,000 regular + 1,000 supplemental = 3,000
        assertEquals(3_000_00L, result.gross.amount)

        // Supplemental basis should be 1,000 only
        val suppBasis = result.trace.steps
            .filterIsInstance<TraceStep.BasisComputed>()
            .first { it.basis == TaxBasis.SupplementalWages }
        assertEquals(1_000_00L, suppBasis.result.amount)

        val tax = result.employeeTaxes.single()
        assertEquals(ruleId, tax.ruleId)
        // 10% of 1,000 = 100
        assertEquals(100_00L, tax.amount.amount)

        // Net = gross - supplemental tax = 3,000 - 100 = 2,900
        assertEquals(2_900_00L, result.net.amount)
    }

    @Test
    fun `regular and supplemental rules both apply to their respective bases`() {
        val employerId = EmployerId("emp-supp-combined")
        val employeeId = EmployeeId("ee-supp-combined")

        val input = baseInputWithSupplemental(employerId, employeeId)

        // Regular flat tax: 10% on Gross (3,000)
        val regRuleId = "REG_FLAT"
        val regRule = TaxRule.FlatRateTax(
            id = regRuleId,
            jurisdiction = TaxJurisdiction(TaxJurisdictionType.FEDERAL, "US"),
            basis = TaxBasis.Gross,
            rate = Percent(0.10),
        )

        // Supplemental flat tax: 22% on SupplementalWages (1,000)
        val suppRuleId = "SUPP_FLAT_22"
        val suppRule = TaxRule.FlatRateTax(
            id = suppRuleId,
            jurisdiction = TaxJurisdiction(TaxJurisdictionType.FEDERAL, "US"),
            basis = TaxBasis.SupplementalWages,
            rate = Percent(0.22),
        )

        val taxedInput = input.copy(
            taxContext = TaxContext(federal = listOf(regRule, suppRule)),
        )

        val result = PayrollEngine.calculatePaycheck(taxedInput)

        // Gross remains the same as before
        assertEquals(3_000_00L, result.gross.amount)

        val taxesByRule = result.employeeTaxes.associateBy { it.ruleId }
        val regTax = taxesByRule.getValue(regRuleId)
        val suppTax = taxesByRule.getValue(suppRuleId)

        // 10% of 3,000 = 300
        assertEquals(3_000_00L, regTax.basis.amount)
        assertEquals(300_00L, regTax.amount.amount)

        // 22% of 1,000 = 220
        assertEquals(1_000_00L, suppTax.basis.amount)
        assertEquals(220_00L, suppTax.amount.amount)

        // Net = 3,000 - (300 + 220) = 2,480
        assertEquals(2_480_00L, result.net.amount)
    }
}
