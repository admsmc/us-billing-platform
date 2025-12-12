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

class FederalWithholdingEngineWageBracketTest {

    private fun baseInput(federalTaxablePerPeriod: Long): Pair<PaycheckInput, BasisComputation> {
        val employerId = EmployerId("emp-fw-wb")
        val employeeId = EmployeeId("ee-fw-wb")
        val period = PayPeriod(
            id = "2025-01-WB1",
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
            paycheckId = PaycheckId("chk-fw-wb"),
            payRunId = PayRunId("run-fw-wb"),
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

    private fun simpleWageBracketRule(id: String = "US_FED_FIT_WB_TEST"): TaxRule.WageBracketTax = TaxRule.WageBracketTax(
        id = id,
        jurisdiction = TaxJurisdiction(TaxJurisdictionType.FEDERAL, "US"),
        basis = TaxBasis.FederalTaxable,
        brackets = listOf(
            WageBracketRow(upTo = Money(1_000_00L), tax = Money(50_00L)), // up to 1,000 -> 50
            WageBracketRow(upTo = Money(2_000_00L), tax = Money(150_00L)), // up to 2,000 -> 150
            WageBracketRow(upTo = null, tax = Money(300_00L)), // above 2,000 -> 300
        ),
        filingStatus = FilingStatus.SINGLE,
    )

    @Test
    fun `wage-bracket method is monotonic across wage bands`() {
        val (inputLow, basesLow) = baseInput(federalTaxablePerPeriod = 800_00L)
        val (inputMid, basesMid) = baseInput(federalTaxablePerPeriod = 1_500_00L)
        val (inputHigh, basesHigh) = baseInput(federalTaxablePerPeriod = 2_500_00L)
        val rule = simpleWageBracketRule()
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
            method = FederalWithholdingEngine.WithholdingMethod.WAGE_BRACKET,
        )

        val mid = FederalWithholdingEngine.computeWithholding(
            input = inputMid,
            bases = basesMid,
            profile = profile,
            federalRules = rules,
            method = FederalWithholdingEngine.WithholdingMethod.WAGE_BRACKET,
        )

        val high = FederalWithholdingEngine.computeWithholding(
            input = inputHigh,
            bases = basesHigh,
            profile = profile,
            federalRules = rules,
            method = FederalWithholdingEngine.WithholdingMethod.WAGE_BRACKET,
        )

        assertEquals(50_00L, low.amount.amount)
        assertEquals(150_00L, mid.amount.amount)
        assertEquals(300_00L, high.amount.amount)

        assertTrue(low.amount.amount < mid.amount.amount)
        assertTrue(mid.amount.amount < high.amount.amount)
    }

    @Test
    fun `wage-bracket method applies extra per-period withholding`() {
        val (input, bases) = baseInput(federalTaxablePerPeriod = 1_500_00L)
        val rule = simpleWageBracketRule()
        val rules = listOf<TaxRule>(rule)

        val baseProfile = WithholdingProfile(
            filingStatus = FilingStatus.SINGLE,
            w4Version = W4Version.MODERN_2020_PLUS,
        )

        val extraProfile = baseProfile.copy(
            extraWithholdingPerPeriod = Money(25_00L),
        )

        val base = FederalWithholdingEngine.computeWithholding(
            input = input,
            bases = bases,
            profile = baseProfile,
            federalRules = rules,
            method = FederalWithholdingEngine.WithholdingMethod.WAGE_BRACKET,
        )

        val withExtra = FederalWithholdingEngine.computeWithholding(
            input = input,
            bases = bases,
            profile = extraProfile,
            federalRules = rules,
            method = FederalWithholdingEngine.WithholdingMethod.WAGE_BRACKET,
        )

        assertEquals(150_00L, base.amount.amount)
        assertEquals(175_00L, withExtra.amount.amount)
        assertTrue(withExtra.amount.amount > base.amount.amount)
    }
}
