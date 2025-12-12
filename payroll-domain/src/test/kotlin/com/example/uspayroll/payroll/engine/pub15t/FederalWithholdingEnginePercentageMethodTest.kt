package com.example.uspayroll.payroll.engine.pub15t

import com.example.uspayroll.payroll.engine.BasisComputation
import com.example.uspayroll.payroll.model.*
import com.example.uspayroll.shared.EmployeeId
import com.example.uspayroll.shared.EmployerId
import com.example.uspayroll.shared.Money
import com.example.uspayroll.shared.PayRunId
import com.example.uspayroll.shared.PaycheckId
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FederalWithholdingEnginePercentageMethodTest {

    private fun baseInput(federalTaxablePerPeriod: Long): Pair<PaycheckInput, BasisComputation> {
        val employerId = EmployerId("emp-fw-pct")
        val employeeId = EmployeeId("ee-fw-pct")
        val period = PayPeriod(
            id = "2025-01-W1",
            employerId = employerId,
            dateRange = LocalDateRange(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 7)),
            checkDate = LocalDate.of(2025, 1, 8),
            frequency = PayFrequency.WEEKLY,
        )
        val snapshot = EmployeeSnapshot(
            employerId = employerId,
            employeeId = employeeId,
            homeState = "CA",
            workState = "CA",
            filingStatus = FilingStatus.SINGLE,
            baseCompensation = BaseCompensation.Salaried(
                annualSalary = Money(52_000_00L),
                frequency = period.frequency,
            ),
        )

        val input = PaycheckInput(
            paycheckId = PaycheckId("chk-fw-pct"),
            payRunId = PayRunId("run-fw-pct"),
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

        val bases = BasisComputation(
            bases = mapOf(TaxBasis.FederalTaxable to Money(federalTaxablePerPeriod)),
            components = emptyMap(),
        )

        return input to bases
    }

    private fun simpleFitRule(id: String = "US_FED_FIT_TEST"): TaxRule.BracketedIncomeTax = TaxRule.BracketedIncomeTax(
        id = id,
        jurisdiction = TaxJurisdiction(TaxJurisdictionType.FEDERAL, "US"),
        basis = TaxBasis.FederalTaxable,
        brackets = listOf(
            TaxBracket(upTo = null, rate = Percent(0.10)),
        ),
        standardDeduction = null,
        additionalWithholding = null,
        filingStatus = FilingStatus.SINGLE,
    )

    @Test
    fun `percentage method is monotonic in wages`() {
        val (inputLow, basesLow) = baseInput(federalTaxablePerPeriod = 1_000_00L)
        val (inputHigh, basesHigh) = baseInput(federalTaxablePerPeriod = 2_000_00L)
        val rule = simpleFitRule()
        val rules = listOf<TaxRule>(rule)

        val profile = WithholdingProfile(
            filingStatus = FilingStatus.SINGLE,
            w4Version = W4Version.MODERN_2020_PLUS,
        )

        val low = FederalWithholdingEngine.computeWithholding(
            input = inputLow,
            bases = basesLow,
            profile = profile,
            federalRules = rules,
            method = FederalWithholdingEngine.WithholdingMethod.PERCENTAGE,
        )

        val high = FederalWithholdingEngine.computeWithholding(
            input = inputHigh,
            bases = basesHigh,
            profile = profile,
            federalRules = rules,
            method = FederalWithholdingEngine.WithholdingMethod.PERCENTAGE,
        )

        assertTrue(high.amount.amount > low.amount.amount)
        // 10% weekly, with 52 periods per year
        assertEquals(100_00L, low.amount.amount)
        assertEquals(200_00L, high.amount.amount)
    }

    @Test
    fun `step 3 annual credit reduces withholding`() {
        val (input, bases) = baseInput(federalTaxablePerPeriod = 1_000_00L)
        val rule = simpleFitRule()
        val rules = listOf<TaxRule>(rule)

        val noCreditProfile = WithholdingProfile(
            filingStatus = FilingStatus.SINGLE,
            w4Version = W4Version.MODERN_2020_PLUS,
        )

        val withCreditProfile = noCreditProfile.copy(
            step3AnnualCredit = Money(1_040_00L), // $20 per week for 52 weeks
        )

        val noCredit = FederalWithholdingEngine.computeWithholding(
            input = input,
            bases = bases,
            profile = noCreditProfile,
            federalRules = rules,
            method = FederalWithholdingEngine.WithholdingMethod.PERCENTAGE,
        )

        val withCredit = FederalWithholdingEngine.computeWithholding(
            input = input,
            bases = bases,
            profile = withCreditProfile,
            federalRules = rules,
            method = FederalWithholdingEngine.WithholdingMethod.PERCENTAGE,
        )

        assertEquals(100_00L, noCredit.amount.amount)
        assertEquals(80_00L, withCredit.amount.amount)
        assertTrue(withCredit.amount.amount < noCredit.amount.amount)
    }

    @Test
    fun `step 4 other income increases and deductions decrease withholding`() {
        val (input, bases) = baseInput(federalTaxablePerPeriod = 1_000_00L)
        val rule = simpleFitRule()
        val rules = listOf<TaxRule>(rule)

        val baseProfile = WithholdingProfile(
            filingStatus = FilingStatus.SINGLE,
            w4Version = W4Version.MODERN_2020_PLUS,
        )

        val otherIncomeProfile = baseProfile.copy(
            step4OtherIncomeAnnual = Money(5_200_00L), // +$100 per week
        )

        val deductionsProfile = baseProfile.copy(
            step4DeductionsAnnual = Money(5_200_00L), // -$100 per week
        )

        val base = FederalWithholdingEngine.computeWithholding(
            input = input,
            bases = bases,
            profile = baseProfile,
            federalRules = rules,
            method = FederalWithholdingEngine.WithholdingMethod.PERCENTAGE,
        )

        val withOtherIncome = FederalWithholdingEngine.computeWithholding(
            input = input,
            bases = bases,
            profile = otherIncomeProfile,
            federalRules = rules,
            method = FederalWithholdingEngine.WithholdingMethod.PERCENTAGE,
        )

        val withDeductions = FederalWithholdingEngine.computeWithholding(
            input = input,
            bases = bases,
            profile = deductionsProfile,
            federalRules = rules,
            method = FederalWithholdingEngine.WithholdingMethod.PERCENTAGE,
        )

        assertEquals(100_00L, base.amount.amount)
        assertEquals(110_00L, withOtherIncome.amount.amount)
        assertEquals(90_00L, withDeductions.amount.amount)

        assertTrue(withOtherIncome.amount.amount > base.amount.amount)
        assertTrue(withDeductions.amount.amount < base.amount.amount)
    }
}
