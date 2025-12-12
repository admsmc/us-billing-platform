package com.example.uspayroll.payroll.engine

import com.example.uspayroll.payroll.model.*
import com.example.uspayroll.shared.EmployeeId
import com.example.uspayroll.shared.EmployerId
import com.example.uspayroll.shared.Money
import com.example.uspayroll.shared.PayRunId
import com.example.uspayroll.shared.PaycheckId
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class BracketedTaxTest {

    private fun baseInput(employerId: EmployerId, employeeId: EmployeeId, priorYtd: YtdSnapshot): PaycheckInput {
        val period = PayPeriod(
            id = "BR-PERIOD",
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
        return PaycheckInput(
            paycheckId = PaycheckId("chk-br"),
            payRunId = PayRunId("run-br"),
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
            priorYtd = priorYtd,
        )
    }

    @Test
    fun `single bracket acts like flat tax`() {
        val employerId = EmployerId("emp-br-1")
        val employeeId = EmployeeId("ee-br-1")
        val priorYtd = YtdSnapshot(year = 2025)

        val ruleId = "BR_SINGLE"
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

        val input = baseInput(employerId, employeeId, priorYtd)
            .copy(taxContext = TaxContext(federal = listOf(rule)))

        val result = PayrollEngine.calculatePaycheck(input)

        assertEquals(10_000_00L, result.gross.amount)
        val tax = result.employeeTaxes.single()
        assertEquals(ruleId, tax.ruleId)
        // 10% of 10,000 = 1,000
        assertEquals(1_000_00L, tax.amount.amount)
    }

    @Test
    fun `multi bracket tax splits income correctly`() {
        val employerId = EmployerId("emp-br-2")
        val employeeId = EmployeeId("ee-br-2")
        val priorYtd = YtdSnapshot(year = 2025)

        val ruleId = "BR_MULTI"
        val rule = TaxRule.BracketedIncomeTax(
            id = ruleId,
            jurisdiction = TaxJurisdiction(TaxJurisdictionType.FEDERAL, "US"),
            basis = TaxBasis.Gross,
            brackets = listOf(
                TaxBracket(upTo = Money(5_000_00L), rate = Percent(0.10)), // 10% on first 5k
                TaxBracket(upTo = Money(10_000_00L), rate = Percent(0.20)), // 20% on next 5k
                TaxBracket(upTo = null, rate = Percent(0.30)), // 30% above 10k
            ),
            standardDeduction = null,
            additionalWithholding = null,
        )

        val input = baseInput(employerId, employeeId, priorYtd)
            .copy(taxContext = TaxContext(federal = listOf(rule)))

        val result = PayrollEngine.calculatePaycheck(input)

        assertEquals(10_000_00L, result.gross.amount)
        val tax = result.employeeTaxes.single()
        assertEquals(ruleId, tax.ruleId)
        // For 10,000: 5k @10% = 500, 5k @20% = 1,000 -> total 1,500
        assertEquals(1_500_00L, tax.amount.amount)

        // Trace should include bracket applications
        val applied = result.trace.steps.filterIsInstance<TraceStep.TaxApplied>()
            .first { it.ruleId == ruleId }
        val brackets = applied.brackets ?: emptyList()
        // Two brackets should have been applied (5k and 5k)
        assertEquals(2, brackets.size)
        assertEquals(5_000_00L, brackets[0].appliedTo.amount)
        assertEquals(5_000_00L, brackets[1].appliedTo.amount)
    }

    @Test
    fun `standard deduction reduces taxable income to zero`() {
        val employerId = EmployerId("emp-br-3")
        val employeeId = EmployeeId("ee-br-3")
        val priorYtd = YtdSnapshot(year = 2025)

        val ruleId = "BR_STDDED_ZERO"
        val rule = TaxRule.BracketedIncomeTax(
            id = ruleId,
            jurisdiction = TaxJurisdiction(TaxJurisdictionType.FEDERAL, "US"),
            basis = TaxBasis.Gross,
            brackets = listOf(
                TaxBracket(upTo = null, rate = Percent(0.10)),
            ),
            standardDeduction = Money(20_000_00L),
            additionalWithholding = null,
        )

        val input = baseInput(employerId, employeeId, priorYtd)
            .copy(taxContext = TaxContext(federal = listOf(rule)))

        val result = PayrollEngine.calculatePaycheck(input)

        // Gross 10,000 < standard deduction 20,000 -> taxable 0
        assertEquals(10_000_00L, result.gross.amount)
        assertEquals(0, result.employeeTaxes.size)
    }

    @Test
    fun `pre tax 401k reduces federal taxable before bracketed federal tax`() {
        val employerId = EmployerId("emp-br-4")
        val employeeId = EmployeeId("ee-br-4")
        val priorYtd = YtdSnapshot(year = 2025)

        // Simple two-bracket federal tax on FederalTaxable basis
        val ruleId = "BR_FED_FT"
        val rule = TaxRule.BracketedIncomeTax(
            id = ruleId,
            jurisdiction = TaxJurisdiction(TaxJurisdictionType.FEDERAL, "US"),
            basis = TaxBasis.FederalTaxable,
            brackets = listOf(
                TaxBracket(upTo = Money(9_000_00L), rate = Percent(0.10)), // 10% up to 9k
                TaxBracket(upTo = null, rate = Percent(0.20)), // 20% above 9k
            ),
            standardDeduction = null,
            additionalWithholding = null,
        )

        // Pre-tax 401k plan: 10% of gross (10,000) = 1,000, so FederalTaxable = 9,000
        val deductionRepo = object : com.example.uspayroll.payroll.model.config.DeductionConfigRepository {
            override fun findPlansForEmployer(employerId: EmployerId): List<com.example.uspayroll.payroll.model.config.DeductionPlan> = listOf(
                com.example.uspayroll.payroll.model.config.DeductionPlan(
                    id = "PLAN_401K_BR",
                    name = "401k Employee",
                    kind = com.example.uspayroll.payroll.model.config.DeductionKind.PRETAX_RETIREMENT_EMPLOYEE,
                    employeeRate = Percent(0.10),
                ),
            )
        }

        val input = baseInput(employerId, employeeId, priorYtd)
            .copy(taxContext = TaxContext(federal = listOf(rule)))

        val result = PayrollEngine.calculatePaycheck(
            input = input,
            earningConfig = null,
            deductionConfig = deductionRepo,
        )

        // Gross still 10,000
        assertEquals(10_000_00L, result.gross.amount)

        // 401k = 10% of 10,000 = 1,000 pre-tax
        val k401 = result.deductions.first { it.description.contains("401k") }
        assertEquals(1_000_00L, k401.amount.amount)

        // Basis for federal tax rules is FederalTaxable = 9,000
        // Our brackets: 9,000 entirely in first bracket at 10% => 900
        val tax = result.employeeTaxes.single()
        assertEquals(ruleId, tax.ruleId)
        assertEquals(900_00L, tax.amount.amount)
    }
}
