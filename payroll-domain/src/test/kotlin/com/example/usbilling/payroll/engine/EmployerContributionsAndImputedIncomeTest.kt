package com.example.usbilling.payroll.engine

import com.example.usbilling.payroll.model.*
import com.example.usbilling.shared.CustomerId
import com.example.usbilling.shared.UtilityId
import com.example.usbilling.shared.Money
import com.example.usbilling.shared.BillRunId
import com.example.usbilling.shared.BillId
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class EmployerContributionsAndImputedIncomeTest {

    @Test
    fun `employer HSA contribution is tracked in YTD but not taxable`() {
        val employerId = UtilityId("emp-hsa-er")
        val employeeId = CustomerId("ee-hsa-er")

        val period = PayPeriod(
            id = "2025-01-BW-HSA-ER",
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
            baseCompensation = BaseCompensation.Hourly(hourlyRate = Money(50_00L)),
        )

        val input = PaycheckInput(
            paycheckId = BillId("chk-hsa-er"),
            payRunId = BillRunId("run-hsa-er"),
            employerId = employerId,
            employeeId = employeeId,
            period = period,
            employeeSnapshot = snapshot,
            timeSlice = TimeSlice(
                period = period,
                regularHours = 40.0,
                overtimeHours = 0.0,
            ),
            taxContext = TaxContext(),
            priorYtd = YtdSnapshot(year = 2025),
        )

        val employerHsaContribution = EmployerContributionLine(
            code = EmployerContributionCode("HSA_ER"),
            description = "Employer HSA",
            amount = Money(500_00L),
        )

        val result = calculatePaycheckDebug(
            input = input,
            earningConfig = null,
            deductionConfig = null,
            employerContributions = listOf(employerHsaContribution),
        )

        // Cash gross: 40 * 50 = 2,000
        assertEquals(2_000_00L, result.gross.amount)
        // Net equals gross (no employee taxes or deductions), contribution does not affect net
        assertEquals(result.gross.amount, result.net.amount)

        // YTD should include the employer HSA contribution under its code
        val ytdContrib = result.ytdAfter.employerContributionsByCode[EmployerContributionCode("HSA_ER")]
        assertEquals(500_00L, ytdContrib?.amount)
    }

    @Test
    fun `imputed income is taxable but not paid in cash`() {
        val employerId = UtilityId("emp-imputed")
        val employeeId = CustomerId("ee-imputed")

        val period = PayPeriod(
            id = "2025-01-BW-IMPUTED",
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
            baseCompensation = BaseCompensation.Hourly(hourlyRate = Money(50_00L)),
        )

        // Regular cash wages: 40 * 50 = 2,000
        // Imputed GTL benefit: 300 (non-cash, taxable), provided via otherEarnings and config
        val imputedInput = EarningInput(
            code = EarningCode("IMPUTED_GTL"),
            units = 1.0,
            rate = null,
            amount = Money(300_00L),
        )

        val taxRuleId = "IMPUTED_FLAT"
        val taxRule = TaxRule.FlatRateTax(
            id = taxRuleId,
            jurisdiction = TaxJurisdiction(TaxJurisdictionType.FEDERAL, "US"),
            basis = TaxBasis.Gross,
            rate = Percent(0.10),
        )

        val input = PaycheckInput(
            paycheckId = BillId("chk-imputed"),
            payRunId = BillRunId("run-imputed"),
            employerId = employerId,
            employeeId = employeeId,
            period = period,
            employeeSnapshot = snapshot,
            timeSlice = TimeSlice(
                period = period,
                regularHours = 40.0,
                overtimeHours = 0.0,
                otherEarnings = listOf(imputedInput),
            ),
            taxContext = TaxContext(federal = listOf(taxRule)),
            priorYtd = YtdSnapshot(year = 2025),
        )

        // Config repository that marks IMPUTED_GTL as imputed income
        val earningConfig = object : com.example.usbilling.payroll.model.config.EarningConfigRepository {
            override fun findByEmployerAndCode(employerId: UtilityId, code: EarningCode): com.example.usbilling.payroll.model.config.EarningDefinition? = when (code.value) {
                "HOURLY" -> com.example.usbilling.payroll.model.config.EarningDefinition(
                    code = code,
                    displayName = "Hourly Wages",
                    category = EarningCategory.REGULAR,
                    defaultRate = Money(50_00L),
                )
                "IMPUTED_GTL" -> com.example.usbilling.payroll.model.config.EarningDefinition(
                    code = code,
                    displayName = "Imputed GTL",
                    category = EarningCategory.IMPUTED,
                )
                else -> null
            }
        }

        val result = calculatePaycheckDebug(
            input = input,
            earningConfig = earningConfig,
            deductionConfig = null,
        )

        // Cash gross excludes imputed earnings: 40 * 50 = 2,000
        assertEquals(2_000_00L, result.gross.amount)

        // Tax bases should see both cash and imputed income -> 2,300
        val grossBasis = result.trace.steps
            .filterIsInstance<TraceStep.BasisComputed>()
            .first { it.basis == TaxBasis.Gross }
        assertEquals(2_300_00L, grossBasis.result.amount)

        val tax = result.employeeTaxes.single { it.ruleId == taxRuleId }
        // 10% of 2,300 = 230
        assertEquals(230_00L, tax.amount.amount)

        // Net cash pay = 2,000 - 230 = 1,770
        assertEquals(1_770_00L, result.net.amount)
    }
}
