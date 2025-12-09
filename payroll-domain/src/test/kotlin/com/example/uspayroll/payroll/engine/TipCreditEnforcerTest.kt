package com.example.uspayroll.payroll.engine

import com.example.uspayroll.payroll.model.*
import com.example.uspayroll.shared.EmployeeId
import com.example.uspayroll.shared.EmployerId
import com.example.uspayroll.shared.Money
import com.example.uspayroll.shared.PayRunId
import com.example.uspayroll.shared.PaycheckId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import java.time.LocalDate

class TipCreditEnforcerTest {

    private fun baseLaborStandards(): LaborStandardsContext = LaborStandardsContext(
        federalMinimumWage = Money(7_25L),      // $7.25/hr
        youthMinimumWage = null,
        youthMaxAgeYears = null,
        youthMaxConsecutiveDaysFromHire = null,
        federalTippedCashMinimum = Money(2_13L),// $2.13/hr
        tippedMonthlyThreshold = Money(30_00L),
    )

    @Test
    fun `no make-up when cash plus tips already meet minimum wage`() {
        val employerId = EmployerId("emp-tip-ok")
        val employeeId = EmployeeId("ee-tip-ok")
        val period = PayPeriod(
            id = "2025-01-W-TIP-OK",
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
            employmentType = EmploymentType.REGULAR,
            baseCompensation = BaseCompensation.Hourly(hourlyRate = Money(2_13L)),
            isTippedEmployee = true,
        )

        // 40 hours, cash 2.13/hr, tips high enough to clear min wage.
        val timeSlice = TimeSlice(
            period = period,
            regularHours = 40.0,
            overtimeHours = 0.0,
        )

        val earnings = mutableListOf<EarningLine>()
        val cashWages = EarningLine(
            code = EarningCode("HOURLY"),
            category = EarningCategory.REGULAR,
            description = "Cash wages",
            units = 40.0,
            rate = Money(2_13L),
            amount = Money(2_13L * 40L),
        )
        val tips = EarningLine(
            code = EarningCode("TIPS"),
            category = EarningCategory.TIPS,
            description = "Tips",
            units = 40.0,
            rate = null,
            amount = Money(250_00L), // $250 tips
        )
        earnings += cashWages
        earnings += tips

        TipCreditEnforcer.applyTipCreditMakeup(
            input = PaycheckInput(
                paycheckId = PaycheckId("chk-tip-ok"),
                payRunId = PayRunId("run-tip-ok"),
                employerId = employerId,
                employeeId = employeeId,
                period = period,
                employeeSnapshot = snapshot,
                timeSlice = timeSlice,
                taxContext = TaxContext(),
                priorYtd = YtdSnapshot(year = 2025),
            ),
            laborStandards = baseLaborStandards(),
            earnings = earnings,
        )

        val makeUp = earnings.firstOrNull { it.code == EarningCode("TIP_MAKEUP") }
        assertNull(makeUp, "Expected no TIP_MAKEUP line when already above minimum wage")
    }

    @Test
    fun `make-up brings tipped employee up to minimum wage`() {
        val employerId = EmployerId("emp-tip-mu")
        val employeeId = EmployeeId("ee-tip-mu")
        val period = PayPeriod(
            id = "2025-01-W-TIP-MU",
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
            employmentType = EmploymentType.REGULAR,
            baseCompensation = BaseCompensation.Hourly(hourlyRate = Money(2_13L)),
            isTippedEmployee = true,
        )

        val timeSlice = TimeSlice(
            period = period,
            regularHours = 40.0,
            overtimeHours = 0.0,
        )

        val earnings = mutableListOf<EarningLine>()
        val cashWages = EarningLine(
            code = EarningCode("HOURLY"),
            category = EarningCategory.REGULAR,
            description = "Cash wages",
            units = 40.0,
            rate = Money(2_13L),
            amount = Money(2_13L * 40L),
        )
        val tips = EarningLine(
            code = EarningCode("TIPS"),
            category = EarningCategory.TIPS,
            description = "Tips",
            units = 40.0,
            rate = null,
            amount = Money(50_00L), // $50 tips, not enough to reach min wage
        )
        earnings += cashWages
        earnings += tips

        val labor = baseLaborStandards()
        TipCreditEnforcer.applyTipCreditMakeup(
            input = PaycheckInput(
                paycheckId = PaycheckId("chk-tip-mu"),
                payRunId = PayRunId("run-tip-mu"),
                employerId = employerId,
                employeeId = employeeId,
                period = period,
                employeeSnapshot = snapshot,
                timeSlice = timeSlice,
                taxContext = TaxContext(),
                priorYtd = YtdSnapshot(year = 2025),
            ),
            laborStandards = labor,
            earnings = earnings,
        )

        val makeUp = earnings.firstOrNull { it.code == EarningCode("TIP_MAKEUP") }
            ?: error("Expected TIP_MAKEUP line when below minimum wage")

        val totalHours = 40.0
        val cashCents = cashWages.amount.amount
        val tipCents = tips.amount.amount
        val expectedRequiredTotal = (labor.federalMinimumWage.amount * totalHours).toLong()
        val actualTotal = cashCents + tipCents
        val expectedDeficiency = (expectedRequiredTotal - actualTotal).coerceAtLeast(0L)

        assertEquals(expectedDeficiency, makeUp.amount.amount)

        // After make-up, cash + tips + make-up should equal required minimum total
        val finalTotal = cashCents + tipCents + makeUp.amount.amount
        assertEquals(expectedRequiredTotal, finalTotal)
    }
}
