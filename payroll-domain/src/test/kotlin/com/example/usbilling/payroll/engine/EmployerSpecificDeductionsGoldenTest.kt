package com.example.usbilling.payroll.engine

import com.example.usbilling.payroll.model.*
import com.example.usbilling.payroll.model.config.DeductionConfigRepository
import com.example.usbilling.payroll.model.config.DeductionEffect
import com.example.usbilling.payroll.model.config.DeductionKind
import com.example.usbilling.payroll.model.config.DeductionPlan
import com.example.usbilling.shared.CustomerId
import com.example.usbilling.shared.UtilityId
import com.example.usbilling.shared.Money
import com.example.usbilling.shared.BillRunId
import com.example.usbilling.shared.BillId
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Golden-style test comparing two employers with identical wages but different
 * retirement plan tax treatment: one pre-tax 401(k) vs a Roth/post-tax plan at
 * the same percentage. This demonstrates that per-employer deduction config
 * yields different tax bases and net pay.
 */
class EmployerSpecificDeductionsGoldenTest {

    private fun baseInput(employerId: UtilityId, employeeId: CustomerId, taxRule: TaxRule.FlatRateTax): PaycheckInput {
        val period = PayPeriod(
            id = "EMP-DEDS-GOLDEN",
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
                annualSalary = Money(260_000_00L), // yields 10,000 per check
                frequency = period.frequency,
            ),
        )
        return PaycheckInput(
            paycheckId = BillId("chk-emp-deds-${employerId.value}"),
            payRunId = BillRunId("run-emp-deds"),
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
                federal = listOf(taxRule),
            ),
            priorYtd = YtdSnapshot(year = 2025),
        )
    }

    private class EmployerRetirementConfigRepository : DeductionConfigRepository {
        override fun findPlansForEmployer(employerId: UtilityId): List<DeductionPlan> = when (employerId.value) {
            "emp-pretax" -> listOf(
                DeductionPlan(
                    id = "PLAN_401K_PRE",
                    name = "401k Pre-Tax",
                    kind = DeductionKind.PRETAX_RETIREMENT_EMPLOYEE,
                    employeeRate = Percent(0.10), // 10%
                ),
            )
            "emp-roth" -> listOf(
                DeductionPlan(
                    id = "PLAN_ROTH",
                    name = "Roth 401k",
                    kind = DeductionKind.ROTH_RETIREMENT_EMPLOYEE,
                    employeeRate = Percent(0.10), // 10%, but post-tax
                    // Explicitly mark no tax-base reduction (default for ROTH, but made
                    // explicit here for clarity).
                    employeeEffects = setOf(DeductionEffect.NO_TAX_EFFECT),
                ),
            )
            else -> emptyList()
        }
    }

    @Test
    fun `pretax vs roth employer plans yield different tax bases and net pay`() {
        val pretaxEmployer = UtilityId("emp-pretax")
        val rothEmployer = UtilityId("emp-roth")

        // Simple flat federal tax at 10% on FederalTaxable basis.
        val taxRuleId = "EE_PRETAX_VS_ROTH"
        val taxRule = TaxRule.FlatRateTax(
            id = taxRuleId,
            jurisdiction = TaxJurisdiction(TaxJurisdictionType.FEDERAL, "US"),
            basis = TaxBasis.FederalTaxable,
            rate = Percent(0.10),
        )

        val pretaxInput = baseInput(pretaxEmployer, CustomerId("ee-pretax"), taxRule)
        val rothInput = baseInput(rothEmployer, CustomerId("ee-roth"), taxRule)

        val repo = EmployerRetirementConfigRepository()

        val pretaxResult = calculatePaycheckDebug(
            input = pretaxInput,
            earningConfig = null,
            deductionConfig = repo,
        )

        val rothResult = calculatePaycheckDebug(
            input = rothInput,
            earningConfig = null,
            deductionConfig = repo,
        )

        // Shared gross.
        assertEquals(10_000_00L, pretaxResult.gross.amount)
        assertEquals(10_000_00L, rothResult.gross.amount)

        // Both withhold 10% of gross for retirement (1,000), but placement differs.
        val pretaxDed = pretaxResult.deductions.first { it.description.contains("401k Pre-Tax") }
        val rothDed = rothResult.deductions.first { it.description.contains("Roth 401k") }
        assertEquals(1_000_00L, pretaxDed.amount.amount)
        assertEquals(1_000_00L, rothDed.amount.amount)

        // FederalTaxable basis should be 9,000 for pretax and 10,000 for Roth.
        val pretaxBasisStep = pretaxResult.trace.steps.filterIsInstance<TraceStep.BasisComputed>()
            .first { it.basis == TaxBasis.FederalTaxable }
        val rothBasisStep = rothResult.trace.steps.filterIsInstance<TraceStep.BasisComputed>()
            .first { it.basis == TaxBasis.FederalTaxable }

        val pretaxBasis = pretaxBasisStep.result
        val rothBasis = rothBasisStep.result

        assertEquals(Money(9_000_00L), pretaxBasis)
        assertEquals(Money(10_000_00L), rothBasis)

        // Employee federal tax: 10% of basis.
        val pretaxTax = pretaxResult.employeeTaxes.single { it.ruleId == taxRuleId }
        val rothTax = rothResult.employeeTaxes.single { it.ruleId == taxRuleId }

        assertEquals(900_00L, pretaxTax.amount.amount)
        assertEquals(1_000_00L, rothTax.amount.amount)

        // Net pay: pretax = 10,000 - 1,000 - 900 = 8,100; Roth = 10,000 - 1,000 - 1,000 = 8,000.
        assertEquals(8_100_00L, pretaxResult.net.amount)
        assertEquals(8_000_00L, rothResult.net.amount)
    }
}
