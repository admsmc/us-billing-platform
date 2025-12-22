package com.example.uspayroll.payroll.engine

import com.example.uspayroll.payroll.model.*
import com.example.uspayroll.shared.EmployeeId
import com.example.uspayroll.shared.EmployerId
import com.example.uspayroll.shared.Money
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class FicaGatingTest {

    private fun baseSnapshot(employmentType: EmploymentType, grossCents: Long): EmployeeSnapshot = EmployeeSnapshot(
        employerId = EmployerId("EMP1"),
        employeeId = EmployeeId("EE1"),
        homeState = "CA",
        workState = "CA",
        filingStatus = FilingStatus.SINGLE,
        employmentType = employmentType,
        baseCompensation = BaseCompensation.Salaried(
            annualSalary = Money(grossCents * 26),
            frequency = PayFrequency.BIWEEKLY,
        ),
    )

    private fun paycheckInput(snapshot: EmployeeSnapshot, grossCents: Long, priorYtdSsCents: Long, priorYtdMedCents: Long): Pair<PaycheckInput, Map<TaxBasis, Money>> {
        val employerId = snapshot.employerId
        val period = PayPeriod(
            id = "P1",
            employerId = employerId,
            dateRange = LocalDateRange(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 14)),
            checkDate = LocalDate.of(2025, 1, 15),
            frequency = PayFrequency.BIWEEKLY,
        )
        val earnings = listOf(
            EarningLine(
                code = EarningCode("BASE"),
                description = "Base",
                category = EarningCategory.REGULAR,
                units = 1.0,
                rate = null,
                amount = Money(grossCents),
            ),
        )
        val ytd = YtdSnapshot(
            year = 2025,
            wagesByBasis = mapOf(
                TaxBasis.SocialSecurityWages to Money(priorYtdSsCents),
                TaxBasis.MedicareWages to Money(priorYtdMedCents),
            ),
        )
        val basisContext = BasisContext(
            earnings = earnings,
            preTaxDeductions = emptyList(),
            postTaxDeductions = emptyList(),
            plansByCode = emptyMap(),
            ytd = ytd,
        )
        val basisComputation = BasisBuilder.compute(basisContext)
        val input = PaycheckInput(
            paycheckId = com.example.uspayroll.shared.PaycheckId("CHK1"),
            payRunId = com.example.uspayroll.shared.PayRunId("RUN1"),
            employerId = employerId,
            employeeId = snapshot.employeeId,
            period = period,
            employeeSnapshot = snapshot,
            timeSlice = TimeSlice(
                period = period,
                regularHours = 0.0,
                overtimeHours = 0.0,
            ),
            taxContext = TaxContext(
                federal = listOf(
                    TaxRule.FlatRateTax(
                        id = "SS",
                        jurisdiction = TaxJurisdiction(TaxJurisdictionType.FEDERAL, "US"),
                        basis = TaxBasis.SocialSecurityWages,
                        rate = Percent(0.062),
                    ),
                    TaxRule.FlatRateTax(
                        id = "MED",
                        jurisdiction = TaxJurisdiction(TaxJurisdictionType.FEDERAL, "US"),
                        basis = TaxBasis.MedicareWages,
                        rate = Percent(0.0145),
                    ),
                ),
            ),
            priorYtd = ytd,
        )
        return input to basisComputation.bases
    }

    @Test
    fun `household employee below threshold does not incur FICA or Medicare`() {
        val grossCents = 100_00L // $100
        val snapshot = baseSnapshot(EmploymentType.HOUSEHOLD, grossCents)
        val (input, bases) = paycheckInput(snapshot, grossCents, priorYtdSsCents = 0L, priorYtdMedCents = 0L)

        val taxResult = TaxesCalculator.computeTaxes(input, bases, emptyMap())

        assertEquals(0, taxResult.employeeTaxes.size, "Expected no FICA/Medicare taxes for household employee below threshold")
    }

    @Test
    fun `household employee above threshold incurs FICA and Medicare`() {
        val grossCents = 3_000_00L // $3,000
        val snapshot = baseSnapshot(EmploymentType.HOUSEHOLD, grossCents)
        // Prior YTD just below threshold so current period pushes over $2,800
        val (input, bases) = paycheckInput(snapshot, grossCents, priorYtdSsCents = 2_700_00L, priorYtdMedCents = 2_700_00L)

        val taxResult = TaxesCalculator.computeTaxes(input, bases, emptyMap())

        // We expect both SS and Medicare lines to be present
        assertEquals(2, taxResult.employeeTaxes.size, "Expected FICA and Medicare taxes once threshold exceeded")
    }

    @Test
    fun `election worker below threshold does not incur FICA or Medicare`() {
        val grossCents = 100_00L // $100
        val snapshot = baseSnapshot(EmploymentType.ELECTION_WORKER, grossCents)
        val (input, bases) = paycheckInput(snapshot, grossCents, priorYtdSsCents = 0L, priorYtdMedCents = 0L)

        val taxResult = TaxesCalculator.computeTaxes(input, bases, emptyMap())

        assertEquals(0, taxResult.employeeTaxes.size, "Expected no FICA/Medicare taxes for election worker below threshold")
    }

    @Test
    fun `election worker above threshold incurs FICA and Medicare`() {
        val grossCents = 3_000_00L // $3,000
        val snapshot = baseSnapshot(EmploymentType.ELECTION_WORKER, grossCents)
        val (input, bases) = paycheckInput(snapshot, grossCents, priorYtdSsCents = 2_300_00L, priorYtdMedCents = 2_300_00L)

        val taxResult = TaxesCalculator.computeTaxes(input, bases, emptyMap())

        assertEquals(2, taxResult.employeeTaxes.size, "Expected FICA and Medicare taxes once election worker threshold exceeded")
    }

    @Test
    fun `ficaExempt employee has no FICA or Medicare even above thresholds`() {
        val grossCents = 10_000_00L
        val base = baseSnapshot(EmploymentType.REGULAR, grossCents)
        val snapshot = base.copy(ficaExempt = true)
        val (input, bases) = paycheckInput(snapshot, grossCents, priorYtdSsCents = 50_000_00L, priorYtdMedCents = 50_000_00L)

        val taxResult = TaxesCalculator.computeTaxes(input, bases, emptyMap())

        assertEquals(0, taxResult.employeeTaxes.size, "Expected no FICA/Medicare taxes for ficaExempt employee")
    }
}
