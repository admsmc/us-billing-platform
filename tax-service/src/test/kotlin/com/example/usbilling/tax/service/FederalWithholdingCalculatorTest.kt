package com.example.usbilling.tax.service

import com.example.usbilling.payroll.model.*
import com.example.usbilling.shared.EmployeeId
import com.example.usbilling.shared.EmployerId
import com.example.usbilling.shared.Money
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertTrue

class FederalWithholdingCalculatorTest {

    private fun baseInput(
        annualSalaryCents: Long,
        filingStatus: FilingStatus = FilingStatus.SINGLE,
        w4AnnualCreditCents: Long? = null,
        w4OtherIncomeCents: Long? = null,
        w4DeductionsCents: Long? = null,
        additionalPerPeriodCents: Long? = null,
    ): FederalWithholdingInput {
        val employerId = EmployerId("EMP1")
        val employeeId = EmployeeId("EE1")
        val period = PayPeriod(
            id = "P1",
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
            filingStatus = filingStatus,
            baseCompensation = BaseCompensation.Salaried(
                annualSalary = Money(annualSalaryCents),
                frequency = period.frequency,
            ),
            w4AnnualCreditAmount = w4AnnualCreditCents?.let { Money(it) },
            w4OtherIncomeAnnual = w4OtherIncomeCents?.let { Money(it) },
            w4DeductionsAnnual = w4DeductionsCents?.let { Money(it) },
            additionalWithholdingPerPeriod = additionalPerPeriodCents?.let { Money(it) },
        )
        val ytd = YtdSnapshot(year = 2025)

        val input = PaycheckInput(
            paycheckId = com.example.usbilling.shared.PaycheckId("CHK1"),
            payRunId = com.example.usbilling.shared.PayRunId("RUN1"),
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
                federal = listOf(
                    TaxRule.BracketedIncomeTax(
                        id = "FED_SINGLE",
                        jurisdiction = TaxJurisdiction(TaxJurisdictionType.FEDERAL, "US"),
                        basis = TaxBasis.FederalTaxable,
                        brackets = listOf(
                            TaxBracket(upTo = Money(20_000_00L), rate = Percent(0.10)),
                            TaxBracket(upTo = Money(40_000_00L), rate = Percent(0.20)),
                            TaxBracket(upTo = null, rate = Percent(0.30)),
                        ),
                        filingStatus = FilingStatus.SINGLE,
                    ),
                    TaxRule.BracketedIncomeTax(
                        id = "FED_MARRIED",
                        jurisdiction = TaxJurisdiction(TaxJurisdictionType.FEDERAL, "US"),
                        basis = TaxBasis.FederalTaxable,
                        brackets = listOf(
                            TaxBracket(upTo = Money(40_000_00L), rate = Percent(0.10)),
                            TaxBracket(upTo = Money(80_000_00L), rate = Percent(0.20)),
                            TaxBracket(upTo = null, rate = Percent(0.30)),
                        ),
                        filingStatus = FilingStatus.MARRIED,
                    ),
                ),
            ),
            priorYtd = ytd,
        )

        return FederalWithholdingInput(paycheckInput = input)
    }

    @Test
    fun `computes baseline withholding from brackets without W-4 adjustments`() {
        val calc = DefaultFederalWithholdingCalculator()
        val input = baseInput(annualSalaryCents = 30_000_00L, filingStatus = FilingStatus.SINGLE)

        val withholding = calc.computeWithholding(input)

        // We don't assert an exact amount here because the implementation is a
        // simplified experimental model; just ensure we have a positive
        // withholding value.
        assertTrue(withholding.amount > 0L, "Withholding should be positive for non-zero wages")
    }

    @Test
    fun `applies W-4 annual credit to reduce withholding`() {
        val calc = DefaultFederalWithholdingCalculator()
        val input = baseInput(
            annualSalaryCents = 30_000_00L,
            filingStatus = FilingStatus.SINGLE,
            w4AnnualCreditCents = 1_000_00L,
        )

        val withholding = calc.computeWithholding(input)

        // Credit should reduce withholding relative to the no-credit baseline.
        val baseline = DefaultFederalWithholdingCalculator().computeWithholding(
            baseInput(annualSalaryCents = 30_000_00L, filingStatus = FilingStatus.SINGLE),
        )
        assertTrue(
            withholding.amount < baseline.amount,
            "W-4 credit should reduce withholding relative to baseline",
        )
    }

    @Test
    fun `adds additional per-period withholding on top of computed amount`() {
        val calc = DefaultFederalWithholdingCalculator()
        val input = baseInput(
            annualSalaryCents = 30_000_00L,
            filingStatus = FilingStatus.SINGLE,
            additionalPerPeriodCents = 50_00L,
        )

        val withholding = calc.computeWithholding(input)

        val baseline = DefaultFederalWithholdingCalculator().computeWithholding(
            baseInput(annualSalaryCents = 30_000_00L, filingStatus = FilingStatus.SINGLE),
        )
        assertTrue(
            withholding.amount > baseline.amount,
            "Withholding should include additional per-period amount on top of baseline",
        )
    }

    @Test
    fun `selects bracket rule by filing status`() {
        val calc = DefaultFederalWithholdingCalculator()

        val single = baseInput(annualSalaryCents = 60_000_00L, filingStatus = FilingStatus.SINGLE)
        val married = baseInput(annualSalaryCents = 60_000_00L, filingStatus = FilingStatus.MARRIED)

        val singleWithholding = calc.computeWithholding(single)
        val marriedWithholding = calc.computeWithholding(married)

        // With wider brackets for married, effective annual tax should be lower
        // for the same wages, hence per-period withholding is lower.
        assertTrue(
            marriedWithholding.amount < singleWithholding.amount,
            "Married filing status should produce lower withholding than single for the same wages in this test setup",
        )
    }

    @Test
    fun `W-4 other income increases withholding and deductions decrease it`() {
        val calc = DefaultFederalWithholdingCalculator()

        val base = baseInput(annualSalaryCents = 30_000_00L, filingStatus = FilingStatus.SINGLE)
        val withOtherIncome = baseInput(
            annualSalaryCents = 30_000_00L,
            filingStatus = FilingStatus.SINGLE,
            w4OtherIncomeCents = 5_000_00L,
        )
        val withDeductions = baseInput(
            annualSalaryCents = 30_000_00L,
            filingStatus = FilingStatus.SINGLE,
            w4DeductionsCents = 5_000_00L,
        )

        val baseAmt = calc.computeWithholding(base).amount
        val otherIncomeAmt = calc.computeWithholding(withOtherIncome).amount
        val deductionsAmt = calc.computeWithholding(withDeductions).amount

        assertTrue(otherIncomeAmt > baseAmt, "Other income should increase withholding relative to baseline")
        assertTrue(deductionsAmt < baseAmt, "Deductions should decrease withholding relative to baseline")
    }

    // TODO(Phase 3b / future work): Re-enable this unit test if we decide we
    // still want a pure-domain synthetic example in addition to the
    // wage-bracket integration test in FederalWithholdingIntegrationTest.
    // For now, Step 2 numeric behavior is locked in via the biweekly
    // wage-bracket integration test.
    // @Test
    fun `Step 2 multiple jobs checkbox increases withholding relative to standard rule`(): Unit = Unit
}
