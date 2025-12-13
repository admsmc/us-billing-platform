package com.example.uspayroll.worker

import com.example.uspayroll.payroll.engine.PayrollEngine
import com.example.uspayroll.payroll.model.*
import com.example.uspayroll.payroll.model.config.DeductionConfigRepository
import com.example.uspayroll.payroll.model.config.DeductionKind
import com.example.uspayroll.payroll.model.config.DeductionPlan
import com.example.uspayroll.shared.EmployeeId
import com.example.uspayroll.shared.EmployerId
import com.example.uspayroll.shared.Money
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals

/**
 * Worker-service module test that distinguishes employers by pretax vs Roth
 * behavior using a concrete DeductionConfigRepository variant.
 *
 * Two employers share identical wages and a simple 10% flat federal tax rule
 * on FederalTaxable, but they differ in retirement plan tax treatment:
 * - emp-pretax: 10% pre-tax 401(k) reduces FederalTaxable.
 * - emp-roth:   10% Roth contribution with no tax-base reduction.
 */
class EmployerPretaxRothWorkerIntegrationTest {

    private fun baseInput(employerId: EmployerId, employeeId: EmployeeId, taxRule: TaxRule.FlatRateTax): PaycheckInput {
        val period = PayPeriod(
            id = "EMP-PRETAX-ROTH-WORKER",
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
                annualSalary = Money(260_000_00L), // 10,000 per check
                frequency = period.frequency,
            ),
        )
        return PaycheckInput(
            paycheckId = com.example.uspayroll.shared.PaycheckId("chk-worker-pre-roth-${employerId.value}"),
            payRunId = com.example.uspayroll.shared.PayRunId("run-worker-pre-roth"),
            employerId = employerId,
            employeeId = employeeId,
            period = period,
            employeeSnapshot = snapshot,
            timeSlice = TimeSlice(
                period = period,
                regularHours = 0.0,
                overtimeHours = 0.0,
            ),
            taxContext = TaxContext(federal = listOf(taxRule)),
            priorYtd = YtdSnapshot(year = 2025),
        )
    }

    /**
     * Concrete DeductionConfigRepository variant that returns different
     * retirement plans for two employers.
     */
    private class PretaxRothDeductionConfigRepository : DeductionConfigRepository {
        override fun findPlansForEmployer(employerId: EmployerId): List<DeductionPlan> = when (employerId.value) {
            "emp-pretax-worker" -> listOf(
                DeductionPlan(
                    id = "PLAN_401K_PRE_WORKER",
                    name = "401k Pre-Tax Worker",
                    kind = DeductionKind.PRETAX_RETIREMENT_EMPLOYEE,
                    employeeRate = Percent(0.10),
                ),
            )
            "emp-roth-worker" -> listOf(
                DeductionPlan(
                    id = "PLAN_ROTH_WORKER",
                    name = "Roth 401k Worker",
                    kind = DeductionKind.ROTH_RETIREMENT_EMPLOYEE,
                    employeeRate = Percent(0.10),
                    // Default effects for ROTH imply no tax-base reduction.
                ),
            )
            else -> emptyList()
        }
    }

    @Test
    fun `pretax vs roth employers yield different tax bases and net pay`() {
        val pretaxEmployer = EmployerId("emp-pretax-worker")
        val rothEmployer = EmployerId("emp-roth-worker")

        // Simple flat federal tax at 10% on FederalTaxable basis.
        val taxRuleId = "EE_WORKER_PRETAX_VS_ROTH"
        val taxRule = TaxRule.FlatRateTax(
            id = taxRuleId,
            jurisdiction = TaxJurisdiction(TaxJurisdictionType.FEDERAL, "US"),
            basis = TaxBasis.FederalTaxable,
            rate = Percent(0.10),
        )

        val pretaxInput = baseInput(pretaxEmployer, EmployeeId("ee-pretax-worker"), taxRule)
        val rothInput = baseInput(rothEmployer, EmployeeId("ee-roth-worker"), taxRule)

        val repo = PretaxRothDeductionConfigRepository()

        val pretaxResult = PayrollEngine.calculatePaycheckComputation(
            input = pretaxInput,
            computedAt = java.time.Instant.EPOCH,
            traceLevel = com.example.uspayroll.payroll.model.audit.TraceLevel.DEBUG,
            earningConfig = null,
            deductionConfig = repo,
        ).paycheck
        val rothResult = PayrollEngine.calculatePaycheckComputation(
            input = rothInput,
            computedAt = java.time.Instant.EPOCH,
            traceLevel = com.example.uspayroll.payroll.model.audit.TraceLevel.DEBUG,
            earningConfig = null,
            deductionConfig = repo,
        ).paycheck

        // Gross is identical.
        assertEquals(10_000_00L, pretaxResult.gross.amount)
        assertEquals(10_000_00L, rothResult.gross.amount)

        // Both withhold 10% (1,000) for retirement.
        val pretaxDed = pretaxResult.deductions.first { it.description.contains("Pre-Tax Worker") }
        val rothDed = rothResult.deductions.first { it.description.contains("Roth 401k Worker") }
        assertEquals(1_000_00L, pretaxDed.amount.amount)
        assertEquals(1_000_00L, rothDed.amount.amount)

        // FederalTaxable basis traces.
        fun federalTaxable(result: PaycheckResult): Money {
            val step = result.trace.steps.filterIsInstance<TraceStep.BasisComputed>()
                .first { it.basis == TaxBasis.FederalTaxable }
            return step.result
        }

        val pretaxBasis = federalTaxable(pretaxResult)
        val rothBasis = federalTaxable(rothResult)

        assertEquals(Money(9_000_00L), pretaxBasis)
        assertEquals(Money(10_000_00L), rothBasis)

        // Employee federal tax at 10% of basis.
        val pretaxTax = pretaxResult.employeeTaxes.single { it.ruleId == taxRuleId }
        val rothTax = rothResult.employeeTaxes.single { it.ruleId == taxRuleId }

        assertEquals(900_00L, pretaxTax.amount.amount)
        assertEquals(1_000_00L, rothTax.amount.amount)

        // Net pay: pretax = 8,100; Roth = 8,000.
        assertEquals(8_100_00L, pretaxResult.net.amount)
        assertEquals(8_000_00L, rothResult.net.amount)
    }
}
