package com.example.uspayroll.worker

import com.example.uspayroll.payroll.engine.PayrollEngine
import com.example.uspayroll.payroll.model.*
import com.example.uspayroll.shared.EmployeeId
import com.example.uspayroll.shared.EmployerId
import com.example.uspayroll.shared.Money
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertTrue

/**
 * Worker-service level tests that exercise end-to-end federal withholding
 * behavior using the real Pub. 15-T backed tax-service integration, focusing
 * on modern (2020+) W-4 paths.
 */
class FederalWithholdingWorkerIntegrationTest {

    private fun basePeriod(employerId: EmployerId, checkDate: LocalDate, frequency: PayFrequency): PayPeriod =
        PayPeriod(
            id = "FW-WORKER-${frequency}",
            employerId = employerId,
            dateRange = LocalDateRange(checkDate, checkDate),
            checkDate = checkDate,
            frequency = frequency,
        )

    @Test
    fun `married filing status yields lower withholding than single for same annual wages`() {
        val employerId = EmployerId("emp-fw-worker-married-vs-single")
        val checkDate = LocalDate.of(2025, 6, 30)
        val period = basePeriod(employerId, checkDate, PayFrequency.ANNUAL)

        fun snapshot(status: FilingStatus): EmployeeSnapshot =
            EmployeeSnapshot(
                employerId = employerId,
                employeeId = EmployeeId("ee-fw-worker-$status"),
                homeState = "CA",
                workState = "CA",
                filingStatus = status,
                baseCompensation = BaseCompensation.Salaried(
                    annualSalary = Money(60_000_00L),
                    frequency = period.frequency,
                ),
                // Use modern W-4 semantics by default; legacy bridge numerics are
                // covered separately in a deferred Phase 3b.
                w4Version = com.example.uspayroll.payroll.engine.pub15t.W4Version.MODERN_2020_PLUS,
            )

        fun inputFor(status: FilingStatus): PaycheckInput {
            val snap = snapshot(status)
            return PaycheckInput(
                paycheckId = com.example.uspayroll.shared.PaycheckId("chk-fw-worker-$status"),
                payRunId = com.example.uspayroll.shared.PayRunId("run-fw-worker"),
                employerId = employerId,
                employeeId = snap.employeeId,
                period = period,
                employeeSnapshot = snap,
                timeSlice = TimeSlice(
                    period = period,
                    regularHours = 0.0,
                    overtimeHours = 0.0,
                ),
                // For this worker-service level test we rely on the real tax
                // catalogs and DefaultFederalWithholdingCalculator wiring
                // exercised by PayrollEngine via TaxContext.
                taxContext = TaxContext(),
                priorYtd = YtdSnapshot(year = period.checkDate.year),
            )
        }

        val singleResult = PayrollEngine.calculatePaycheck(inputFor(FilingStatus.SINGLE))
        val marriedResult = PayrollEngine.calculatePaycheck(inputFor(FilingStatus.MARRIED))

        val singleFit = singleResult.employeeTaxes
            .filter { it.jurisdiction.type == TaxJurisdictionType.FEDERAL && it.jurisdiction.code == "US" }
            .sumOf { it.amount.amount }
        val marriedFit = marriedResult.employeeTaxes
            .filter { it.jurisdiction.type == TaxJurisdictionType.FEDERAL && it.jurisdiction.code == "US" }
            .sumOf { it.amount.amount }

        assertTrue(marriedFit < singleFit,
            "Expected married withholding to be lower than single for the same wages at the worker-service level")
    }

    @Test
    fun `credits other income and deductions move worker-level FIT in expected directions`() {
        val employerId = EmployerId("emp-fw-worker-w4-behavior")
        val checkDate = LocalDate.of(2025, 6, 30)
        val period = basePeriod(employerId, checkDate, PayFrequency.ANNUAL)

        fun snapshot(
            creditCents: Long? = null,
            otherIncomeCents: Long? = null,
            deductionsCents: Long? = null,
        ): EmployeeSnapshot =
            EmployeeSnapshot(
                employerId = employerId,
                employeeId = EmployeeId("ee-fw-worker-w4"),
                homeState = "CA",
                workState = "CA",
                filingStatus = FilingStatus.SINGLE,
                baseCompensation = BaseCompensation.Salaried(
                    annualSalary = Money(50_000_00L),
                    frequency = period.frequency,
                ),
                w4AnnualCreditAmount = creditCents?.let { Money(it) },
                w4OtherIncomeAnnual = otherIncomeCents?.let { Money(it) },
                w4DeductionsAnnual = deductionsCents?.let { Money(it) },
                w4Version = com.example.uspayroll.payroll.engine.pub15t.W4Version.MODERN_2020_PLUS,
            )

        fun runWith(
            creditCents: Long? = null,
            otherIncomeCents: Long? = null,
            deductionsCents: Long? = null,
        ): Long {
            val snap = snapshot(creditCents, otherIncomeCents, deductionsCents)
            val input = PaycheckInput(
                paycheckId = com.example.uspayroll.shared.PaycheckId("chk-fw-worker-w4"),
                payRunId = com.example.uspayroll.shared.PayRunId("run-fw-worker-w4"),
                employerId = employerId,
                employeeId = snap.employeeId,
                period = period,
                employeeSnapshot = snap,
                timeSlice = TimeSlice(
                    period = period,
                    regularHours = 0.0,
                    overtimeHours = 0.0,
                ),
                taxContext = TaxContext(),
                priorYtd = YtdSnapshot(year = period.checkDate.year),
            )

            val result = PayrollEngine.calculatePaycheck(input)
            return result.employeeTaxes
                .filter { it.jurisdiction.type == TaxJurisdictionType.FEDERAL && it.jurisdiction.code == "US" }
                .sumOf { it.amount.amount }
        }

        val baseline = runWith()
        val withCredit = runWith(creditCents = 2_000_00L)
        val withOtherIncome = runWith(otherIncomeCents = 5_000_00L)
        val withDeductions = runWith(deductionsCents = 5_000_00L)

        assertTrue(withCredit < baseline,
            "Credits should reduce worker-level federal withholding relative to baseline")
        assertTrue(withOtherIncome > baseline,
            "Other income should increase worker-level federal withholding relative to baseline")
        assertTrue(withDeductions < baseline,
            "Deductions should decrease worker-level federal withholding relative to baseline")
    }
}