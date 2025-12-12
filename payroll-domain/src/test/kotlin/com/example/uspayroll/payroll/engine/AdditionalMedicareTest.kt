package com.example.uspayroll.payroll.engine

import com.example.uspayroll.payroll.model.*
import com.example.uspayroll.shared.EmployeeId
import com.example.uspayroll.shared.EmployerId
import com.example.uspayroll.shared.Money
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class AdditionalMedicareTest {

    private fun paycheckInput(currentMedicareWagesCents: Long, priorMedicareWagesCents: Long): Pair<PaycheckInput, Map<TaxBasis, Money>> {
        val employerId = EmployerId("EMP-MED")
        val employeeId = EmployeeId("EE-MED")
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
            filingStatus = FilingStatus.SINGLE,
            baseCompensation = BaseCompensation.Salaried(
                annualSalary = Money(100_000_00L),
                frequency = period.frequency,
            ),
        )

        val earnings = listOf(
            EarningLine(
                code = EarningCode("BASE"),
                description = "Base",
                category = EarningCategory.REGULAR,
                units = 1.0,
                rate = null,
                amount = Money(currentMedicareWagesCents),
            ),
        )
        val ytd = YtdSnapshot(
            year = 2025,
            wagesByBasis = mapOf(
                TaxBasis.MedicareWages to Money(priorMedicareWagesCents),
            ),
        )

        val basisContext = BasisContext(
            earnings = earnings,
            preTaxDeductions = emptyList(),
            postTaxDeductions = emptyList(),
            plansByCode = emptyMap(),
            ytd = ytd,
        )
        val bases = BasisBuilder.compute(basisContext)

        val input = PaycheckInput(
            paycheckId = com.example.uspayroll.shared.PaycheckId("CHK-MED"),
            payRunId = com.example.uspayroll.shared.PayRunId("RUN-MED"),
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
                    // Base Medicare 1.45%
                    TaxRule.FlatRateTax(
                        id = "US_FICA_MED_EMP_2025",
                        jurisdiction = TaxJurisdiction(TaxJurisdictionType.FEDERAL, "US"),
                        basis = TaxBasis.MedicareWages,
                        rate = Percent(0.0145),
                    ),
                    // Additional Medicare 0.9% above 200,000
                    TaxRule.BracketedIncomeTax(
                        id = "US_FED_ADDITIONAL_MEDICARE_2025",
                        jurisdiction = TaxJurisdiction(TaxJurisdictionType.FEDERAL, "US"),
                        basis = TaxBasis.MedicareWages,
                        brackets = listOf(
                            TaxBracket(upTo = Money(200_000_00L), rate = Percent(0.0)),
                            TaxBracket(upTo = null, rate = Percent(0.009)),
                        ),
                    ),
                ),
            ),
            priorYtd = ytd,
        )
        return input to bases.bases
    }

    @Test
    fun `additional medicare does not apply below threshold`() {
        val current = 10_000_00L
        val prior = 100_000_00L
        val (input, bases) = paycheckInput(currentMedicareWagesCents = current, priorMedicareWagesCents = prior)

        val taxResult = TaxesCalculator.computeTaxes(input, bases, emptyMap())

        val baseLine = taxResult.employeeTaxes.find { it.ruleId == "US_FICA_MED_EMP_2025" } ?: error("Missing base Medicare")
        val addl = taxResult.employeeTaxes.find { it.ruleId == "US_FED_ADDITIONAL_MEDICARE_2025" }

        assertEquals((current * 0.0145).toLong(), baseLine.amount.amount)
        assertEquals(null, addl, "Expected no Additional Medicare below 200k threshold")
    }

    @Test
    fun `additional medicare applies only to wages above threshold`() {
        val current = 50_000_00L
        val prior = 190_000_00L
        val (input, bases) = paycheckInput(currentMedicareWagesCents = current, priorMedicareWagesCents = prior)

        val taxResult = TaxesCalculator.computeTaxes(input, bases, emptyMap())

        val baseLine = taxResult.employeeTaxes.find { it.ruleId == "US_FICA_MED_EMP_2025" } ?: error("Missing base Medicare")
        val addl = taxResult.employeeTaxes.find { it.ruleId == "US_FED_ADDITIONAL_MEDICARE_2025" }

        // Base Medicare applies on full current wages
        assertEquals((current * 0.0145).toLong(), baseLine.amount.amount)

        // Additional Medicare applies only to portion above 200k threshold.
        val overThreshold = (prior + current - 200_000_00L).coerceAtLeast(0L)
        val expectedAddl = (overThreshold * 0.009).toLong()
        val actualAddl = addl?.amount?.amount ?: 0L
        assertEquals(expectedAddl, actualAddl)
    }
}
