package com.example.usbilling.payroll.engine

import com.example.usbilling.payroll.model.*
import com.example.usbilling.payroll.model.config.DeductionConfigRepository
import com.example.usbilling.payroll.model.config.DeductionKind
import com.example.usbilling.payroll.model.config.DeductionPlan
import com.example.usbilling.shared.EmployeeId
import com.example.usbilling.shared.EmployerId
import com.example.usbilling.shared.Money
import com.example.usbilling.shared.PayRunId
import com.example.usbilling.shared.PaycheckId
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Golden-style test comparing three employers with identical wages but
 * different health benefit configurations: HSA vs FSA vs none. Demonstrates
 * that per-employer plans lead to different federal tax bases and net pay.
 */
class EmployerSpecificHsaFsaGoldenTest {

    private fun baseInput(employerId: EmployerId, employeeId: EmployeeId, taxRule: TaxRule.FlatRateTax): PaycheckInput {
        val period = PayPeriod(
            id = "EMP-HSA-FSA-GOLDEN",
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
            paycheckId = PaycheckId("chk-hsa-fsa-${employerId.value}"),
            payRunId = PayRunId("run-hsa-fsa"),
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

    private class MultiEmployerHsaFsaRepo : DeductionConfigRepository {
        override fun findPlansForEmployer(employerId: EmployerId): List<DeductionPlan> = when (employerId.value) {
            "emp-hsa" -> listOf(
                DeductionPlan(
                    id = "PLAN_HSA",
                    name = "HSA",
                    kind = DeductionKind.HSA,
                    employeeRate = Percent(0.10), // 10% of gross
                ),
            )
            "emp-fsa" -> listOf(
                DeductionPlan(
                    id = "PLAN_FSA",
                    name = "FSA",
                    kind = DeductionKind.FSA,
                    employeeRate = Percent(0.10), // 10% of gross
                ),
            )
            "emp-none" -> emptyList()
            else -> emptyList()
        }
    }

    @Test
    fun `HSA vs FSA vs none yield different federal tax bases and net pay`() {
        val hsaEmployer = EmployerId("emp-hsa")
        val fsaEmployer = EmployerId("emp-fsa")
        val noneEmployer = EmployerId("emp-none")

        // Simple flat federal tax at 10% on FederalTaxable basis.
        val taxRuleId = "EE_HSA_FSA_BASELINE"
        val taxRule = TaxRule.FlatRateTax(
            id = taxRuleId,
            jurisdiction = TaxJurisdiction(TaxJurisdictionType.FEDERAL, "US"),
            basis = TaxBasis.FederalTaxable,
            rate = Percent(0.10),
        )

        val hsaInput = baseInput(hsaEmployer, EmployeeId("ee-hsa"), taxRule)
        val fsaInput = baseInput(fsaEmployer, EmployeeId("ee-fsa"), taxRule)
        val noneInput = baseInput(noneEmployer, EmployeeId("ee-none"), taxRule)

        val repo = MultiEmployerHsaFsaRepo()

        val hsaResult = calculatePaycheckDebug(
            input = hsaInput,
            earningConfig = null,
            deductionConfig = repo,
        )
        val fsaResult = calculatePaycheckDebug(
            input = fsaInput,
            earningConfig = null,
            deductionConfig = repo,
        )
        val noneResult = calculatePaycheckDebug(
            input = noneInput,
            earningConfig = null,
            deductionConfig = repo,
        )

        // All have the same gross.
        assertEquals(10_000_00L, hsaResult.gross.amount)
        assertEquals(10_000_00L, fsaResult.gross.amount)
        assertEquals(10_000_00L, noneResult.gross.amount)

        // HSA and FSA each withhold 10% (1,000); none has no deductions.
        val hsaDed = hsaResult.deductions.single()
        val fsaDed = fsaResult.deductions.single()
        assertEquals(1_000_00L, hsaDed.amount.amount)
        assertEquals(1_000_00L, fsaDed.amount.amount)
        assertEquals(0, noneResult.deductions.size)

        // FederalTaxable basis for all three.
        fun federalTaxable(result: PaycheckResult): Money {
            val step = result.trace.steps.filterIsInstance<TraceStep.BasisComputed>()
                .first { it.basis == TaxBasis.FederalTaxable }
            return step.result
        }

        val hsaBasis = federalTaxable(hsaResult)
        val fsaBasis = federalTaxable(fsaResult)
        val noneBasis = federalTaxable(noneResult)

        // HSA and FSA default effects both reduce FederalTaxable, so bases match.
        assertEquals(Money(9_000_00L), hsaBasis)
        assertEquals(Money(9_000_00L), fsaBasis)
        // No plan -> no reduction.
        assertEquals(Money(10_000_00L), noneBasis)

        // Employee federal tax: 10% of basis.
        val hsaTax = hsaResult.employeeTaxes.single { it.ruleId == taxRuleId }
        val fsaTax = fsaResult.employeeTaxes.single { it.ruleId == taxRuleId }
        val noneTax = noneResult.employeeTaxes.single { it.ruleId == taxRuleId }

        assertEquals(900_00L, hsaTax.amount.amount)
        assertEquals(900_00L, fsaTax.amount.amount)
        assertEquals(1_000_00L, noneTax.amount.amount)

        // Net pay:
        // - HSA/FSA: 10,000 - 1,000 - 900 = 8,100
        // - None:    10,000 - 0     - 1,000 = 9,000
        assertEquals(8_100_00L, hsaResult.net.amount)
        assertEquals(8_100_00L, fsaResult.net.amount)
        assertEquals(9_000_00L, noneResult.net.amount)
    }
}
