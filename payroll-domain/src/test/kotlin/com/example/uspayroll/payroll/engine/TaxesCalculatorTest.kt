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

class TaxesCalculatorTest {

    @Test
    fun `employer flat tax on gross is applied and accumulated in YTD`() {
        val employerId = EmployerId("emp-1")
        val employeeId = EmployeeId("ee-3")
        val period = PayPeriod(
            id = "2025-01-BW2",
            employerId = employerId,
            dateRange = LocalDateRange(LocalDate.of(2025, 1, 15), LocalDate.of(2025, 1, 28)),
            checkDate = LocalDate.of(2025, 1, 29),
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

        // Employer flat tax: 10% of gross
        val ruleId = "ER_TEST"
        val rule = TaxRule.FlatRateTax(
            id = ruleId,
            jurisdiction = TaxJurisdiction(TaxJurisdictionType.FEDERAL, "US"),
            basis = TaxBasis.Gross,
            rate = Percent(0.10),
        )

        val input = PaycheckInput(
            paycheckId = PaycheckId("chk-3"),
            payRunId = PayRunId("run-2"),
            employerId = employerId,
            employeeId = employeeId,
            period = period,
            employeeSnapshot = snapshot,
            timeSlice = TimeSlice(
                period = period,
                regularHours = 0.0,
                overtimeHours = 0.0,
            ),
            taxContext = TaxContext(
                employerSpecific = listOf(rule),
            ),
            priorYtd = YtdSnapshot(year = 2025),
        )

        val result = PayrollEngine.calculatePaycheck(input)

        // Gross should still be 10,000 (as in the salaried test)
        assertEquals(10_000_00L, result.gross.amount)
        // Employer tax: 10% of 10,000 = 1,000
        val employerTax = result.employerTaxes.single()
        assertEquals(ruleId, employerTax.ruleId)
        assertEquals(1_000_00L, employerTax.amount.amount)
        // Net remains unchanged (employer tax only)
        assertEquals(10_000_00L, result.net.amount)

        // YTD should accumulate employer tax by rule id
        val ytdEmployerTax = result.ytdAfter.employerTaxesByRuleId[ruleId]
        assertEquals(1_000_00L, ytdEmployerTax?.amount)
    }

    @Test
    fun `employee flat tax reduces net and updates YTD`() {
        val employerId = EmployerId("emp-1")
        val employeeId = EmployeeId("ee-6")
        val period = PayPeriod(
            id = "2025-01-BW-EE",
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
        )

        val ruleId = "EE_TEST"
        val rule = TaxRule.FlatRateTax(
            id = ruleId,
            jurisdiction = TaxJurisdiction(TaxJurisdictionType.FEDERAL, "US"),
            basis = TaxBasis.Gross,
            rate = Percent(0.10),
        )

        val input = PaycheckInput(
            paycheckId = PaycheckId("chk-ee"),
            payRunId = PayRunId("run-ee"),
            employerId = employerId,
            employeeId = employeeId,
            period = period,
            employeeSnapshot = snapshot,
            timeSlice = TimeSlice(
                period = period,
                regularHours = 0.0,
                overtimeHours = 0.0,
            ),
            taxContext = TaxContext(
                federal = listOf(rule),
            ),
            priorYtd = YtdSnapshot(year = 2025),
        )

        val result = PayrollEngine.calculatePaycheck(input)

        assertEquals(10_000_00L, result.gross.amount)
        val employeeTax = result.employeeTaxes.single()
        assertEquals(ruleId, employeeTax.ruleId)
        assertEquals(1_000_00L, employeeTax.amount.amount)
        // Net is gross - employee tax
        assertEquals(9_000_00L, result.net.amount)

        val ytdEmployeeTax = result.ytdAfter.employeeTaxesByRuleId[ruleId]
        assertEquals(1_000_00L, ytdEmployeeTax?.amount)

        // Trace should contain a Gross basis and tax application for this rule
        val hasGrossBasis = result.trace.steps.any { it is TraceStep.BasisComputed && it.basis == TaxBasis.Gross }
        val hasTaxApplied = result.trace.steps.any {
            it is TraceStep.TaxApplied && it.ruleId == ruleId && it.amount.amount == 1_000_00L
        }
        assertEquals(true, hasGrossBasis)
        assertEquals(true, hasTaxApplied)
    }

    @Test
    fun `employee and employer taxes both applied`() {
        val employerId = EmployerId("emp-1")
        val employeeId = EmployeeId("ee-7")
        val period = PayPeriod(
            id = "2025-01-BW-BOTH",
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
        )

        val eeRuleId = "EE_TEST2"
        val erRuleId = "ER_TEST2"
        val eeRule = TaxRule.FlatRateTax(
            id = eeRuleId,
            jurisdiction = TaxJurisdiction(TaxJurisdictionType.FEDERAL, "US"),
            basis = TaxBasis.Gross,
            rate = Percent(0.10),
        )
        val erRule = TaxRule.FlatRateTax(
            id = erRuleId,
            jurisdiction = TaxJurisdiction(TaxJurisdictionType.STATE, "CA"),
            basis = TaxBasis.Gross,
            rate = Percent(0.05),
        )

        val input = PaycheckInput(
            paycheckId = PaycheckId("chk-both"),
            payRunId = PayRunId("run-both"),
            employerId = employerId,
            employeeId = employeeId,
            period = period,
            employeeSnapshot = snapshot,
            timeSlice = TimeSlice(
                period = period,
                regularHours = 0.0,
                overtimeHours = 0.0,
            ),
            taxContext = TaxContext(
                federal = listOf(eeRule),
                employerSpecific = listOf(erRule),
            ),
            priorYtd = YtdSnapshot(year = 2025),
        )

        val result = PayrollEngine.calculatePaycheck(input)

        assertEquals(10_000_00L, result.gross.amount)

        val eeTax = result.employeeTaxes.single()
        assertEquals(eeRuleId, eeTax.ruleId)
        assertEquals(1_000_00L, eeTax.amount.amount)

        val erTax = result.employerTaxes.single()
        assertEquals(erRuleId, erTax.ruleId)
        assertEquals(500_00L, erTax.amount.amount)

        // Net is gross - employee tax only
        assertEquals(9_000_00L, result.net.amount)

        val ytdEeTax = result.ytdAfter.employeeTaxesByRuleId[eeRuleId]
        val ytdErTax = result.ytdAfter.employerTaxesByRuleId[erRuleId]
        assertEquals(1_000_00L, ytdEeTax?.amount)
        assertEquals(500_00L, ytdErTax?.amount)
    }
}
