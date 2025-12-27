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

class YtdYearBoundaryTest {

    @Test
    fun `YTD accumulates earnings and taxes across checks in same year`() {
        val employerId = UtilityId("emp-ytd-1")
        val employeeId = CustomerId("ee-ytd-1")

        val period1 = PayPeriod(
            id = "2025-01-BW1",
            employerId = employerId,
            dateRange = LocalDateRange(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 14)),
            checkDate = LocalDate.of(2025, 1, 15),
            frequency = PayFrequency.BIWEEKLY,
        )
        val period2 = PayPeriod(
            id = "2025-01-BW2",
            employerId = employerId,
            dateRange = LocalDateRange(LocalDate.of(2025, 1, 15), LocalDate.of(2025, 1, 28)),
            checkDate = LocalDate.of(2025, 1, 29),
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
                frequency = PayFrequency.BIWEEKLY,
            ),
        )

        val ruleId = "YTD_FLAT"
        val rule = TaxRule.FlatRateTax(
            id = ruleId,
            jurisdiction = TaxJurisdiction(TaxJurisdictionType.FEDERAL, "US"),
            basis = TaxBasis.Gross,
            rate = Percent(0.10),
        )

        val input1 = PaycheckInput(
            paycheckId = BillId("chk-ytd-1"),
            payRunId = BillRunId("run-ytd-1"),
            employerId = employerId,
            employeeId = employeeId,
            period = period1,
            employeeSnapshot = snapshot,
            timeSlice = TimeSlice(
                period = period1,
                regularHours = 0.0,
                overtimeHours = 0.0,
            ),
            taxContext = TaxContext(federal = listOf(rule)),
            priorYtd = YtdSnapshot(year = 2025),
        )

        val result1 = calculatePaycheckDebug(input1)

        val input2 = input1.copy(
            paycheckId = BillId("chk-ytd-2"),
            payRunId = BillRunId("run-ytd-2"),
            period = period2,
            priorYtd = result1.ytdAfter,
        )

        val result2 = calculatePaycheckDebug(input2)

        // Each biweekly gross = 10,000; each tax = 1,000
        assertEquals(10_000_00L, result1.gross.amount)
        assertEquals(10_000_00L, result2.gross.amount)

        val ytd = result2.ytdAfter

        // Earnings by code (BASE) should be 20,000 total
        val baseCode = EarningCode("BASE")
        assertEquals(20_000_00L, ytd.earningsByCode[baseCode]?.amount)

        // Employee tax YTD for the rule should be 2,000
        assertEquals(2_000_00L, ytd.employeeTaxesByRuleId[ruleId]?.amount)

        // Wages by basis for Gross should be 20,000
        assertEquals(20_000_00L, ytd.wagesByBasis[TaxBasis.Gross]?.amount)
    }

    @Test
    fun `YTD year mismatch produces trace note`() {
        val employerId = UtilityId("emp-ytd-2")
        val employeeId = CustomerId("ee-ytd-2")

        // Prior YTD is for 2025 but check date is in 2026
        val priorYtd = YtdSnapshot(year = 2025)

        val period = PayPeriod(
            id = "2026-01-BW1",
            employerId = employerId,
            dateRange = LocalDateRange(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 14)),
            checkDate = LocalDate.of(2026, 1, 15),
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
                frequency = PayFrequency.BIWEEKLY,
            ),
        )

        val input = PaycheckInput(
            paycheckId = BillId("chk-ytd-mismatch"),
            payRunId = BillRunId("run-ytd-mismatch"),
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

        val result = calculatePaycheckDebug(input)

        // Trace should contain a Note about YTD year mismatch
        val mismatchNotes = result.trace.steps.filterIsInstance<TraceStep.Note>()
            .filter { it.message.contains("ytd_year_mismatch") }
        assertEquals(1, mismatchNotes.size)
        // Message should reflect the prior YTD year and the check year
        val msg = mismatchNotes.first().message
        assert(msg.contains("prior=2025"))
        assert(msg.contains("checkYear=2026"))
    }
}
