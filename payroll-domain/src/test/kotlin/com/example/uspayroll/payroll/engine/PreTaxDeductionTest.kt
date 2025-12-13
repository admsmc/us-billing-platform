package com.example.uspayroll.payroll.engine

import com.example.uspayroll.payroll.model.*
import com.example.uspayroll.payroll.model.config.DeductionConfigRepository
import com.example.uspayroll.payroll.model.config.DeductionKind
import com.example.uspayroll.payroll.model.config.DeductionPlan
import com.example.uspayroll.shared.EmployeeId
import com.example.uspayroll.shared.EmployerId
import com.example.uspayroll.shared.Money
import com.example.uspayroll.shared.PayRunId
import com.example.uspayroll.shared.PaycheckId
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

private class PreTax401kConfigRepository : DeductionConfigRepository {
    override fun findPlansForEmployer(employerId: EmployerId): List<DeductionPlan> = listOf(
        DeductionPlan(
            id = "PLAN_401K",
            name = "401k Employee",
            kind = DeductionKind.PRETAX_RETIREMENT_EMPLOYEE,
            employeeRate = Percent(0.10), // 10%
        ),
    )
}

class PreTaxDeductionTest {

    @Test
    fun `pre tax 401k reduces tax basis and net pay`() {
        val employerId = EmployerId("emp-1")
        val employeeId = EmployeeId("ee-401k")
        val period = PayPeriod(
            id = "2025-01-BW-401K",
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
                annualSalary = Money(260_000_00L),
                frequency = period.frequency,
            ),
        )

        val taxRuleId = "EE_TAX_401K"
        val taxRule = TaxRule.FlatRateTax(
            id = taxRuleId,
            jurisdiction = TaxJurisdiction(TaxJurisdictionType.FEDERAL, "US"),
            basis = TaxBasis.FederalTaxable,
            rate = Percent(0.10), // 10%
        )

        val input = PaycheckInput(
            paycheckId = PaycheckId("chk-401k"),
            payRunId = PayRunId("run-401k"),
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
                federal = listOf(taxRule),
            ),
            priorYtd = YtdSnapshot(year = 2025),
        )

        val result = calculatePaycheckDebug(
            input = input,
            earningConfig = null,
            deductionConfig = PreTax401kConfigRepository(),
        )

        // Gross is still 10,000
        assertEquals(10_000_00L, result.gross.amount)

        // 401k = 10% of 10,000 = 1,000 pre-tax
        val preTax401k = result.deductions.first { it.description.contains("401k") }
        assertEquals(1_000_00L, preTax401k.amount.amount)

        // Tax basis should be 9,000, so employee tax = 900
        val eeTax = result.employeeTaxes.single()
        assertEquals(taxRuleId, eeTax.ruleId)
        assertEquals(900_00L, eeTax.amount.amount)

        // Net = gross - 401k - employee tax = 10,000 - 1,000 - 900 = 8,100
        assertEquals(8_100_00L, result.net.amount)

        // YTD should include 401k deduction under its plan code
        val ytd401k = result.ytdAfter.deductionsByCode[DeductionCode("PLAN_401K")]
        assertEquals(1_000_00L, ytd401k?.amount)
    }
}
