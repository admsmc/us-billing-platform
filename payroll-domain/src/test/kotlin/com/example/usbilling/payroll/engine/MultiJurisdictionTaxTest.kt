package com.example.usbilling.payroll.engine

import com.example.usbilling.payroll.model.BaseCompensation
import com.example.usbilling.payroll.model.EmployeeSnapshot
import com.example.usbilling.payroll.model.FilingStatus
import com.example.usbilling.payroll.model.LocalDateRange
import com.example.usbilling.payroll.model.PayFrequency
import com.example.usbilling.payroll.model.PayPeriod
import com.example.usbilling.payroll.model.PaycheckInput
import com.example.usbilling.payroll.model.Percent
import com.example.usbilling.payroll.model.TaxBasis
import com.example.usbilling.payroll.model.TaxContext
import com.example.usbilling.payroll.model.TaxJurisdiction
import com.example.usbilling.payroll.model.TaxJurisdictionType
import com.example.usbilling.payroll.model.TaxRule
import com.example.usbilling.payroll.model.TimeSlice
import com.example.usbilling.payroll.model.YtdSnapshot
import com.example.usbilling.shared.CustomerId
import com.example.usbilling.shared.UtilityId
import com.example.usbilling.shared.Money
import com.example.usbilling.shared.BillingCycleId
import com.example.usbilling.shared.BillId
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class MultiJurisdictionTaxTest {

    private fun basePeriod(employerId: UtilityId): PayPeriod = PayPeriod(
        id = "2025-01-BW-MULTI",
        employerId = employerId,
        dateRange = LocalDateRange(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 14)),
        checkDate = LocalDate.of(2025, 1, 15),
        frequency = PayFrequency.BIWEEKLY,
    )

    private fun baseInput(
        employerId: UtilityId,
        employeeId: CustomerId,
        homeState: String,
        workState: String,
        additionalWithholding: Money? = null,
        localityAllocations: Map<String, Double> = emptyMap(),
        taxContext: TaxContext,
    ): PaycheckInput {
        val period = basePeriod(employerId)
        val snapshot = EmployeeSnapshot(
            employerId = employerId,
            employeeId = employeeId,
            homeState = homeState,
            workState = workState,
            filingStatus = FilingStatus.SINGLE,
            baseCompensation = BaseCompensation.Salaried(
                annualSalary = Money(260_000_00L),
                frequency = period.frequency,
            ),
            additionalWithholdingPerPeriod = additionalWithholding,
        )

        return PaycheckInput(
            paycheckId = BillId("chk-multi-${employeeId.value}"),
            payRunId = BillingCycleId("run-multi"),
            employerId = employerId,
            employeeId = employeeId,
            period = period,
            employeeSnapshot = snapshot,
            timeSlice = TimeSlice(
                period = period,
                regularHours = 0.0,
                overtimeHours = 0.0,
                localityAllocations = localityAllocations,
            ),
            taxContext = taxContext,
            priorYtd = YtdSnapshot(year = period.checkDate.year),
        )
    }

    @Test
    fun `employee can have resident and work state taxes applied concurrently`() {
        val employerId = UtilityId("EMP-MULTI-STATE")
        val employeeId = CustomerId("EE-MULTI-STATE")

        val federalRuleId = "US_FED_10"
        val caRuleId = "CA_SIT_5"
        val nyRuleId = "NY_SIT_7"

        val federal = TaxRule.FlatRateTax(
            id = federalRuleId,
            jurisdiction = TaxJurisdiction(TaxJurisdictionType.FEDERAL, "US"),
            basis = TaxBasis.Gross,
            rate = Percent(0.10),
        )

        val caState = TaxRule.FlatRateTax(
            id = caRuleId,
            jurisdiction = TaxJurisdiction(TaxJurisdictionType.STATE, "CA"),
            basis = TaxBasis.Gross,
            rate = Percent(0.05),
        )

        val nyState = TaxRule.FlatRateTax(
            id = nyRuleId,
            jurisdiction = TaxJurisdiction(TaxJurisdictionType.STATE, "NY"),
            basis = TaxBasis.Gross,
            rate = Percent(0.07),
        )

        val input = baseInput(
            employerId = employerId,
            employeeId = employeeId,
            homeState = "CA",
            workState = "NY",
            additionalWithholding = Money(50_00L),
            taxContext = TaxContext(
                federal = listOf(federal),
                state = listOf(caState, nyState),
            ),
        )

        val result = calculatePaycheckDebug(input)

        // 260,000 / 26 = 10,000 per biweekly paycheck.
        assertEquals(10_000_00L, result.gross.amount)

        val taxesByRule = result.employeeTaxes.associateBy { it.ruleId }
        val fed = taxesByRule[federalRuleId]
        val ca = taxesByRule[caRuleId]
        val ny = taxesByRule[nyRuleId]

        assertNotNull(fed)
        assertNotNull(ca)
        assertNotNull(ny)

        // Additional withholding should apply once (to the first federal income tax rule).
        assertEquals(1_050_00L, fed.amount.amount)
        assertEquals(500_00L, ca.amount.amount)
        assertEquals(700_00L, ny.amount.amount)

        assertEquals(7_750_00L, result.net.amount)
    }

    @Test
    fun `local taxes split bases across multiple localities when no explicit allocation is provided`() {
        val employerId = UtilityId("EMP-MULTI-LOCAL")
        val employeeId = CustomerId("EE-MULTI-LOCAL")

        val detroitRuleId = "MI_DETROIT_LOCAL"
        val lansingRuleId = "MI_LANSING_LOCAL"

        val detroit = TaxRule.FlatRateTax(
            id = detroitRuleId,
            jurisdiction = TaxJurisdiction(TaxJurisdictionType.LOCAL, "MI_DETROIT"),
            basis = TaxBasis.Gross,
            rate = Percent(0.02),
            localityFilter = "DETROIT",
        )

        val lansing = TaxRule.FlatRateTax(
            id = lansingRuleId,
            jurisdiction = TaxJurisdiction(TaxJurisdictionType.LOCAL, "MI_LANSING"),
            basis = TaxBasis.Gross,
            rate = Percent(0.01),
            localityFilter = "LANSING",
        )

        val input = baseInput(
            employerId = employerId,
            employeeId = employeeId,
            homeState = "MI",
            workState = "MI",
            taxContext = TaxContext(
                local = listOf(detroit, lansing),
            ),
        )

        val result = calculatePaycheckDebug(input)

        assertEquals(10_000_00L, result.gross.amount)

        val taxesByRule = result.employeeTaxes.associateBy { it.ruleId }
        val detroitTax = taxesByRule[detroitRuleId]
        val lansingTax = taxesByRule[lansingRuleId]

        assertNotNull(detroitTax)
        assertNotNull(lansingTax)

        // With two localities and no explicit allocation, split gross basis evenly.
        assertEquals(5_000_00L, detroitTax.basis.amount)
        assertEquals(5_000_00L, lansingTax.basis.amount)

        // Taxes: 2% of 5,000 = 100; 1% of 5,000 = 50.
        assertEquals(100_00L, detroitTax.amount.amount)
        assertEquals(50_00L, lansingTax.amount.amount)

        assertEquals(9_850_00L, result.net.amount)
    }
}
