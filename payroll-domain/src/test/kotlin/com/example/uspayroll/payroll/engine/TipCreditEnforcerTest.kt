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

    private fun txLaborStandards(): LaborStandardsContext = LaborStandardsContext(
        federalMinimumWage = Money(7_25L),      // $7.25/hr TX baseline
        youthMinimumWage = null,
        youthMaxAgeYears = null,
        youthMaxConsecutiveDaysFromHire = null,
        federalTippedCashMinimum = Money(2_13L),// $2.13/hr TX tipped cash minimum
        tippedMonthlyThreshold = Money(30_00L),
    )

    private fun caLaborStandards(): LaborStandardsContext = LaborStandardsContext(
        federalMinimumWage = Money(16_50L),     // $16.50/hr CA statewide minimum
        youthMinimumWage = null,
        youthMaxAgeYears = null,
        youthMaxConsecutiveDaysFromHire = null,
        // CA does not allow a separate tipped-cash floor; cash + tips must reach
        // the full minimum wage, so we leave federalTippedCashMinimum null.
        federalTippedCashMinimum = null,
        tippedMonthlyThreshold = Money(30_00L),
    )

    @Test
    fun `no make-up when cash plus tips already meet minimum wage (TX profile)`() {
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
            laborStandards = txLaborStandards(),
            earnings = earnings,
        )

        val makeUp = earnings.firstOrNull { it.code == EarningCode("TIP_MAKEUP") }
        assertNull(makeUp, "Expected no TIP_MAKEUP line when already above minimum wage")
    }

    @Test
    fun `make-up brings tipped employee up to minimum wage (TX profile)`() {
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

        val labor = txLaborStandards()
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

    @Test
    fun `CA tipped employee uses higher state minimum wage with no tip credit`() {
        val employerId = EmployerId("emp-tip-ca")
        val employeeId = EmployeeId("ee-tip-ca")
        val period = PayPeriod(
            id = "2025-01-W-TIP-CA",
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
            baseCompensation = BaseCompensation.Hourly(hourlyRate = Money(10_00L)),
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
            rate = Money(10_00L),
            amount = Money(10_00L * 40L),
        )
        val tips = EarningLine(
            code = EarningCode("TIPS"),
            category = EarningCategory.TIPS,
            description = "Tips",
            units = 40.0,
            rate = null,
            amount = Money(100_00L), // $100 tips
        )
        earnings += cashWages
        earnings += tips

        val labor = caLaborStandards()
        TipCreditEnforcer.applyTipCreditMakeup(
            input = PaycheckInput(
                paycheckId = PaycheckId("chk-tip-ca"),
                payRunId = PayRunId("run-tip-ca"),
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
            ?: error("Expected TIP_MAKEUP line when below CA minimum wage")

        val totalHours = 40.0
        val cashCents = cashWages.amount.amount
        val tipCents = tips.amount.amount
        val requiredTotal = (labor.federalMinimumWage.amount * totalHours).toLong() // 16.50 * 40
        val actualTotal = cashCents + tipCents
        val expectedDeficiency = (requiredTotal - actualTotal).coerceAtLeast(0L)

        assertEquals(expectedDeficiency, makeUp.amount.amount)
        assertEquals(requiredTotal, cashCents + tipCents + makeUp.amount.amount)
    }
}
