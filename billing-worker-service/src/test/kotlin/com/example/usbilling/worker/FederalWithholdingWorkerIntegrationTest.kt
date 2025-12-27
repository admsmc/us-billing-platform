package com.example.usbilling.worker

import com.example.usbilling.payroll.model.*
import com.example.usbilling.shared.CustomerId
import com.example.usbilling.shared.UtilityId
import com.example.usbilling.shared.Money
import com.example.usbilling.tax.service.DefaultFederalWithholdingCalculator
import com.example.usbilling.tax.service.FederalWithholdingInput
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertTrue

/**
 * Worker-service level tests that exercise end-to-end federal withholding
 * behavior using the real Pub. 15-T backed tax-service integration, focusing
 * on modern (2020+) W-4 paths.
 */
class FederalWithholdingWorkerIntegrationTest {

    private fun basePeriod(employerId: UtilityId, checkDate: LocalDate, frequency: PayFrequency): PayPeriod = PayPeriod(
        id = "FW-WORKER-$frequency",
        employerId = employerId,
        dateRange = LocalDateRange(checkDate, checkDate),
        checkDate = checkDate,
        frequency = frequency,
    )

    @Test
    fun `married filing status yields lower withholding than single for same annual wages`() {
        val employerId = UtilityId("emp-fw-worker-married-vs-single")
        val checkDate = LocalDate.of(2025, 6, 30)
        val period = basePeriod(employerId, checkDate, PayFrequency.ANNUAL)

        val fitSingle = TaxRule.BracketedIncomeTax(
            id = "US_FIT_SINGLE",
            jurisdiction = TaxJurisdiction(TaxJurisdictionType.FEDERAL, "US"),
            basis = TaxBasis.FederalTaxable,
            brackets = listOf(TaxBracket(upTo = null, rate = Percent(0.10))),
            standardDeduction = Money(12_000_00L),
            filingStatus = FilingStatus.SINGLE,
        )

        val fitMarried = TaxRule.BracketedIncomeTax(
            id = "US_FIT_MARRIED",
            jurisdiction = TaxJurisdiction(TaxJurisdictionType.FEDERAL, "US"),
            basis = TaxBasis.FederalTaxable,
            brackets = listOf(TaxBracket(upTo = null, rate = Percent(0.10))),
            standardDeduction = Money(24_000_00L),
            filingStatus = FilingStatus.MARRIED,
        )

        val taxContext = TaxContext(federal = listOf(fitSingle, fitMarried))
        val calculator = DefaultFederalWithholdingCalculator(method = "PERCENTAGE")

        fun snapshot(status: FilingStatus): EmployeeSnapshot = EmployeeSnapshot(
            employerId = employerId,
            employeeId = CustomerId("ee-fw-worker-$status"),
            homeState = "CA",
            workState = "CA",
            filingStatus = status,
            baseCompensation = BaseCompensation.Salaried(
                annualSalary = Money(60_000_00L),
                frequency = period.frequency,
            ),
            w4Version = com.example.usbilling.payroll.engine.pub15t.W4Version.MODERN_2020_PLUS,
        )

        fun inputFor(status: FilingStatus): PaycheckInput {
            val snap = snapshot(status)
            return PaycheckInput(
                paycheckId = com.example.usbilling.shared.BillId("chk-fw-worker-$status"),
                payRunId = com.example.usbilling.shared.BillingCycleId("run-fw-worker"),
                employerId = employerId,
                employeeId = snap.employeeId,
                period = period,
                employeeSnapshot = snap,
                timeSlice = TimeSlice(
                    period = period,
                    regularHours = 0.0,
                    overtimeHours = 0.0,
                ),
                taxContext = taxContext,
                priorYtd = YtdSnapshot(year = period.checkDate.year),
            )
        }

        val singleWithholding = calculator.computeWithholding(FederalWithholdingInput(inputFor(FilingStatus.SINGLE))).amount
        val marriedWithholding = calculator.computeWithholding(FederalWithholdingInput(inputFor(FilingStatus.MARRIED))).amount

        assertTrue(
            marriedWithholding < singleWithholding,
            "Expected married withholding to be lower than single for the same wages at the worker-service level",
        )
    }

    @Test
    fun `credits other income and deductions move worker-level FIT in expected directions`() {
        val employerId = UtilityId("emp-fw-worker-w4-behavior")
        val checkDate = LocalDate.of(2025, 6, 30)
        val period = basePeriod(employerId, checkDate, PayFrequency.ANNUAL)

        val fitSingle = TaxRule.BracketedIncomeTax(
            id = "US_FIT_SINGLE",
            jurisdiction = TaxJurisdiction(TaxJurisdictionType.FEDERAL, "US"),
            basis = TaxBasis.FederalTaxable,
            brackets = listOf(TaxBracket(upTo = null, rate = Percent(0.10))),
            standardDeduction = Money(12_000_00L),
            filingStatus = FilingStatus.SINGLE,
        )
        val taxContext = TaxContext(federal = listOf(fitSingle))
        val calculator = DefaultFederalWithholdingCalculator(method = "PERCENTAGE")

        fun snapshot(creditCents: Long? = null, otherIncomeCents: Long? = null, deductionsCents: Long? = null): EmployeeSnapshot = EmployeeSnapshot(
            employerId = employerId,
            employeeId = CustomerId("ee-fw-worker-w4"),
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
            w4Version = com.example.usbilling.payroll.engine.pub15t.W4Version.MODERN_2020_PLUS,
        )

        fun runWith(creditCents: Long? = null, otherIncomeCents: Long? = null, deductionsCents: Long? = null): Long {
            val snap = snapshot(creditCents, otherIncomeCents, deductionsCents)
            val input = PaycheckInput(
                paycheckId = com.example.usbilling.shared.BillId("chk-fw-worker-w4"),
                payRunId = com.example.usbilling.shared.BillingCycleId("run-fw-worker-w4"),
                employerId = employerId,
                employeeId = snap.employeeId,
                period = period,
                employeeSnapshot = snap,
                timeSlice = TimeSlice(
                    period = period,
                    regularHours = 0.0,
                    overtimeHours = 0.0,
                ),
                taxContext = taxContext,
                priorYtd = YtdSnapshot(year = period.checkDate.year),
            )

            return calculator.computeWithholding(FederalWithholdingInput(input)).amount
        }

        val baseline = runWith()
        val withCredit = runWith(creditCents = 2_000_00L)
        val withOtherIncome = runWith(otherIncomeCents = 5_000_00L)
        val withDeductions = runWith(deductionsCents = 5_000_00L)

        assertTrue(
            withCredit < baseline,
            "Credits should reduce worker-level federal withholding relative to baseline",
        )
        assertTrue(
            withOtherIncome > baseline,
            "Other income should increase worker-level federal withholding relative to baseline",
        )
        assertTrue(
            withDeductions < baseline,
            "Deductions should decrease worker-level federal withholding relative to baseline",
        )
    }
}
