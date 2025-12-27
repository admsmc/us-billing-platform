package com.example.usbilling.payroll.engine

import com.example.usbilling.payroll.model.*
import com.example.usbilling.shared.CustomerId
import com.example.usbilling.shared.UtilityId
import com.example.usbilling.shared.Money
import com.example.usbilling.shared.BillingCycleId
import com.example.usbilling.shared.BillId
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class FicaTaxTest {

    private fun baseInput(employerId: UtilityId, employeeId: CustomerId, priorYtd: YtdSnapshot, grossAnnual: Long = 260_000_00L): PaycheckInput {
        val period = PayPeriod(
            id = "FICA-PERIOD",
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
                annualSalary = Money(grossAnnual),
                frequency = period.frequency,
            ),
        )
        return PaycheckInput(
            paycheckId = BillId("chk-fica"),
            payRunId = BillingCycleId("run-fica"),
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
            priorYtd = priorYtd,
        )
    }

    @Test
    fun `social security flat tax applies fully when below wage cap`() {
        val employerId = UtilityId("emp-ss-1")
        val employeeId = CustomerId("ee-ss-1")
        val priorYtd = YtdSnapshot(year = 2025)

        val ssRuleId = "SS_EMP"
        val ssRule = TaxRule.FlatRateTax(
            id = ssRuleId,
            jurisdiction = TaxJurisdiction(TaxJurisdictionType.FEDERAL, "SS"),
            basis = TaxBasis.SocialSecurityWages,
            rate = Percent(0.10),
            annualWageCap = Money(20_000_00L),
        )

        val input = baseInput(employerId, employeeId, priorYtd)
            .copy(taxContext = TaxContext(federal = listOf(ssRule)))

        val result = calculatePaycheckDebug(input)

        // Salaried tests use biweekly gross of 10,000
        assertEquals(10_000_00L, result.gross.amount)
        val ssTax = result.employeeTaxes.single()
        assertEquals(ssRuleId, ssTax.ruleId)
        // 10% of 10,000 = 1,000
        assertEquals(1_000_00L, ssTax.amount.amount)
    }

    @Test
    fun `social security flat tax is limited when crossing wage cap`() {
        val employerId = UtilityId("emp-ss-2")
        val employeeId = CustomerId("ee-ss-2")
        val priorYtd = YtdSnapshot(
            year = 2025,
            wagesByBasis = mapOf(
                TaxBasis.SocialSecurityWages to Money(15_000_00L),
            ),
        )

        val ssRuleId = "SS_EMP_CAP"
        val ssRule = TaxRule.FlatRateTax(
            id = ssRuleId,
            jurisdiction = TaxJurisdiction(TaxJurisdictionType.FEDERAL, "SS"),
            basis = TaxBasis.SocialSecurityWages,
            rate = Percent(0.10),
            annualWageCap = Money(20_000_00L),
        )

        val input = baseInput(employerId, employeeId, priorYtd)
            .copy(taxContext = TaxContext(federal = listOf(ssRule)))

        val result = calculatePaycheckDebug(input)

        // Gross is 10,000; prior wages = 15,000; cap = 20,000 -> only 5,000 taxable this period
        assertEquals(10_000_00L, result.gross.amount)
        val ssTax = result.employeeTaxes.single()
        assertEquals(ssRuleId, ssTax.ruleId)
        // 10% of 5,000 = 500
        assertEquals(500_00L, ssTax.amount.amount)
        // Basis on the tax line should reflect only the taxed wages
        assertEquals(5_000_00L, ssTax.basis.amount)
    }

    @Test
    fun `social security tax is zero once wage cap already exceeded`() {
        val employerId = UtilityId("emp-ss-3")
        val employeeId = CustomerId("ee-ss-3")
        val priorYtd = YtdSnapshot(
            year = 2025,
            wagesByBasis = mapOf(
                TaxBasis.SocialSecurityWages to Money(25_000_00L),
            ),
        )

        val ssRuleId = "SS_EMP_OVER"
        val ssRule = TaxRule.FlatRateTax(
            id = ssRuleId,
            jurisdiction = TaxJurisdiction(TaxJurisdictionType.FEDERAL, "SS"),
            basis = TaxBasis.SocialSecurityWages,
            rate = Percent(0.10),
            annualWageCap = Money(20_000_00L),
        )

        val input = baseInput(employerId, employeeId, priorYtd)
            .copy(taxContext = TaxContext(federal = listOf(ssRule)))

        val result = calculatePaycheckDebug(input)

        assertEquals(10_000_00L, result.gross.amount)
        // No SS tax lines because we were already above the wage cap
        assertEquals(0, result.employeeTaxes.size)
    }

    @Test
    fun `medicare employee tax applies on full wages when uncapped`() {
        val employerId = UtilityId("emp-med-ee")
        val employeeId = CustomerId("ee-med-ee")
        val priorYtd = YtdSnapshot(
            year = 2025,
            wagesByBasis = mapOf(
                // Even with prior Medicare wages, uncapped rule still applies to full basis
                TaxBasis.MedicareWages to Money(1_000_000_00L),
            ),
        )

        val medicareRuleId = "MED_EMP"
        val medicareRule = TaxRule.FlatRateTax(
            id = medicareRuleId,
            jurisdiction = TaxJurisdiction(TaxJurisdictionType.FEDERAL, "MED"),
            basis = TaxBasis.MedicareWages,
            rate = Percent(0.02), // 2%
            annualWageCap = null, // uncapped
        )

        val input = baseInput(employerId, employeeId, priorYtd)
            .copy(taxContext = TaxContext(federal = listOf(medicareRule)))

        val result = calculatePaycheckDebug(input)

        assertEquals(10_000_00L, result.gross.amount)
        val tax = result.employeeTaxes.single()
        assertEquals(medicareRuleId, tax.ruleId)
        // 2% of 10,000 = 200 regardless of prior YTD
        assertEquals(200_00L, tax.amount.amount)
        assertEquals(10_000_00L, tax.basis.amount)
    }

    @Test
    fun `employee and employer social security taxes share same basis and cap`() {
        val employerId = UtilityId("emp-ss-both")
        val employeeId = CustomerId("ee-ss-both")
        val priorYtd = YtdSnapshot(year = 2025)

        val ssEmployeeRuleId = "SS_EMP_BOTH"
        val ssEmployerRuleId = "SS_ER_BOTH"

        val ssEmployeeRule = TaxRule.FlatRateTax(
            id = ssEmployeeRuleId,
            jurisdiction = TaxJurisdiction(TaxJurisdictionType.FEDERAL, "SS"),
            basis = TaxBasis.SocialSecurityWages,
            rate = Percent(0.062), // 6.2% employee
            annualWageCap = Money(20_000_00L),
        )
        val ssEmployerRule = TaxRule.FlatRateTax(
            id = ssEmployerRuleId,
            jurisdiction = TaxJurisdiction(TaxJurisdictionType.FEDERAL, "SS"),
            basis = TaxBasis.SocialSecurityWages,
            rate = Percent(0.062), // 6.2% employer
            annualWageCap = Money(20_000_00L),
        )

        val input = baseInput(employerId, employeeId, priorYtd).copy(
            taxContext = TaxContext(
                federal = listOf(ssEmployeeRule),
                employerSpecific = listOf(ssEmployerRule),
            ),
        )

        val result = calculatePaycheckDebug(input)

        assertEquals(10_000_00L, result.gross.amount)

        val eeTax = result.employeeTaxes.single()
        val erTax = result.employerTaxes.single()

        // Both see the same SocialSecurityWages basis and cap, so both tax 10,000 at 6.2%
        assertEquals(ssEmployeeRuleId, eeTax.ruleId)
        assertEquals(ssEmployerRuleId, erTax.ruleId)
        assertEquals(620_00L, eeTax.amount.amount)
        assertEquals(620_00L, erTax.amount.amount)
        assertEquals(10_000_00L, eeTax.basis.amount)
        assertEquals(10_000_00L, erTax.basis.amount)
    }
}
