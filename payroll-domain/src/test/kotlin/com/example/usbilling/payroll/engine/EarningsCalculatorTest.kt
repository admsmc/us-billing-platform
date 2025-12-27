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

class EarningsCalculatorTest {

    @Test
    fun `salaried employee gross is annual divided by biweekly frequency`() {
        val employerId = UtilityId("emp-1")
        val employeeId = CustomerId("ee-1")
        val period = PayPeriod(
            id = "2025-01-BW1",
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
                annualSalary = Money(260_000_00L), // $260,000
                frequency = period.frequency,
            ),
        )
        val input = PaycheckInput(
            paycheckId = BillId("chk-1"),
            payRunId = BillingCycleId("run-1"),
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

        val result = calculatePaycheckDebug(input)

        // 260,000 annual / 26 biweekly periods = 10,000 per period
        assertEquals(10_000_00L, result.gross.amount)
        assertEquals(result.gross.amount, result.net.amount)
    }

    @Test
    fun `salaried employee gross uses correct divisors for other frequencies`() {
        val employerId = UtilityId("emp-freq")
        val employeeId = CustomerId("ee-freq")
        val annual = Money(260_000_00L)

        fun makeInput(freq: PayFrequency, periodId: String): PaycheckInput {
            val period = PayPeriod(
                id = periodId,
                employerId = employerId,
                dateRange = LocalDateRange(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 14)),
                checkDate = LocalDate.of(2025, 1, 15),
                frequency = freq,
            )
            val snapshot = EmployeeSnapshot(
                employerId = employerId,
                employeeId = employeeId,
                homeState = "CA",
                workState = "CA",
                filingStatus = FilingStatus.SINGLE,
                baseCompensation = BaseCompensation.Salaried(
                    annualSalary = annual,
                    frequency = freq,
                ),
            )
            return PaycheckInput(
                paycheckId = BillId("chk-$periodId"),
                payRunId = BillingCycleId("run-$periodId"),
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
        }

        fun grossFor(freq: PayFrequency, periodId: String): Long = calculatePaycheckDebug(makeInput(freq, periodId)).gross.amount

        // 260,000 / 52 weekly = 5,000
        assertEquals(5_000_00L, grossFor(PayFrequency.WEEKLY, "WEEKLY"))
        // 260,000 / 13 four-weekly = 20,000
        assertEquals(20_000_00L, grossFor(PayFrequency.FOUR_WEEKLY, "FOUR_WEEKLY"))
        // 260,000 / 24 semi-monthly ≈ 10,833.33 -> integer division truncates
        assertEquals(10_833_33L, grossFor(PayFrequency.SEMI_MONTHLY, "SEMI_MONTHLY"))
        // 260,000 / 12 monthly ≈ 21,666.66 -> integer division truncates
        assertEquals(21_666_66L, grossFor(PayFrequency.MONTHLY, "MONTHLY"))
        // 260,000 / 4 quarterly = 65,000
        assertEquals(65_000_00L, grossFor(PayFrequency.QUARTERLY, "QUARTERLY"))
        // 260,000 / 1 annual = 260,000
        assertEquals(260_000_00L, grossFor(PayFrequency.ANNUAL, "ANNUAL"))
    }

    @Test
    fun `hourly employee gross is hourly rate times hours`() {
        val employerId = UtilityId("emp-1")
        val employeeId = CustomerId("ee-2")
        val period = PayPeriod(
            id = "2025-01-W1",
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
            baseCompensation = BaseCompensation.Hourly(hourlyRate = Money(50_00L)), // $50/hr
        )
        val input = PaycheckInput(
            paycheckId = BillId("chk-2"),
            payRunId = BillingCycleId("run-1"),
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

        val result = calculatePaycheckDebug(input)

        // 40 hours * $50 = $2,000
        assertEquals(2_000_00L, result.gross.amount)
        assertEquals(result.gross.amount, result.net.amount)
    }

    @Test
    fun `salaried half-period proration reduces gross proportionally`() {
        val employerId = UtilityId("emp-prorate")
        val employeeId = CustomerId("ee-prorate")
        val annual = Money(120_000_00L) // cleanly divisible by 24 -> 5,000 per full period

        val period = PayPeriod(
            id = "2025-01-SM1",
            employerId = employerId,
            dateRange = LocalDateRange(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 15)),
            checkDate = LocalDate.of(2025, 1, 15),
            frequency = PayFrequency.SEMI_MONTHLY,
            sequenceInYear = 1,
        )
        val snapshot = EmployeeSnapshot(
            employerId = employerId,
            employeeId = employeeId,
            homeState = "CA",
            workState = "CA",
            filingStatus = FilingStatus.SINGLE,
            baseCompensation = BaseCompensation.Salaried(
                annualSalary = annual,
                frequency = period.frequency,
            ),
        )

        val halfPeriodInput = PaycheckInput(
            paycheckId = BillId("chk-prorate-half"),
            payRunId = BillingCycleId("run-prorate"),
            employerId = employerId,
            employeeId = employeeId,
            period = period,
            employeeSnapshot = snapshot,
            timeSlice = TimeSlice(
                period = period,
                regularHours = 0.0,
                overtimeHours = 0.0,
                proration = Proration(0.5),
            ),
            taxContext = TaxContext(),
            priorYtd = YtdSnapshot(year = 2025),
        )

        val fullPeriodInput = halfPeriodInput.copy(
            paycheckId = BillId("chk-prorate-full"),
            timeSlice = halfPeriodInput.timeSlice.copy(proration = null),
        )

        val fullResult = calculatePaycheckDebug(fullPeriodInput)
        val halfResult = calculatePaycheckDebug(halfPeriodInput)

        // Full period: 120,000 / 24 = 5,000
        assertEquals(5_000_00L, fullResult.gross.amount)
        // Half period: 2,500
        assertEquals(2_500_00L, halfResult.gross.amount)
    }

    @Test
    fun `salaried calendar-day proration based on hire date`() {
        val employerId = UtilityId("emp-hire")
        val employeeId = CustomerId("ee-hire")
        val annual = Money(120_000_00L) // 24 periods -> 5,000 full-period

        // Period: 15 days (1st to 15th), hire date on the 8th -> 8 days worked
        val period = PayPeriod(
            id = "2025-02-SM1",
            employerId = employerId,
            dateRange = LocalDateRange(LocalDate.of(2025, 2, 1), LocalDate.of(2025, 2, 15)),
            checkDate = LocalDate.of(2025, 2, 15),
            frequency = PayFrequency.SEMI_MONTHLY,
            sequenceInYear = 2,
        )
        val snapshot = EmployeeSnapshot(
            employerId = employerId,
            employeeId = employeeId,
            homeState = "CA",
            workState = "CA",
            filingStatus = FilingStatus.SINGLE,
            baseCompensation = BaseCompensation.Salaried(
                annualSalary = annual,
                frequency = period.frequency,
            ),
            hireDate = LocalDate.of(2025, 2, 8),
        )

        val input = PaycheckInput(
            paycheckId = BillId("chk-hire-prorate"),
            payRunId = BillingCycleId("run-hire-prorate"),
            employerId = employerId,
            employeeId = employeeId,
            period = period,
            employeeSnapshot = snapshot,
            timeSlice = TimeSlice(
                period = period,
                regularHours = 0.0,
                overtimeHours = 0.0,
                proration = null, // rely on strategy
            ),
            taxContext = TaxContext(),
            priorYtd = YtdSnapshot(year = 2025),
        )

        val result = calculatePaycheckDebug(input)

        val fullPeriodCents = annual.amount / 24 // 5,000
        val expectedFraction = 8.0 / 15.0
        val expectedCents = (fullPeriodCents * expectedFraction).toLong()

        assertEquals(expectedCents, result.gross.amount)

        // And proration summary helper should reflect the same information
        val summary = result.prorationSummary()!!
        assertEquals("CalendarDays", summary.strategy)
        assertEquals(false, summary.explicitOverride)
        assertEquals(expectedFraction, summary.fraction)
        assertEquals(fullPeriodCents, summary.fullCents)
        assertEquals(expectedCents, summary.appliedCents)
    }

    @Test
    fun `salaried allocation uses sequenceInYear when present`() {
        val employerId = UtilityId("emp-seq")
        val employeeId = CustomerId("ee-seq")
        val annual = Money(100_000_00L) // chosen so 24 periods yields a remainder

        fun makeInput(sequenceInYear: Int): PaycheckInput {
            val period = PayPeriod(
                id = "PER-$sequenceInYear",
                employerId = employerId,
                dateRange = LocalDateRange(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 14)),
                checkDate = LocalDate.of(2025, 1, 15),
                frequency = PayFrequency.SEMI_MONTHLY,
                sequenceInYear = sequenceInYear,
            )
            val snapshot = EmployeeSnapshot(
                employerId = employerId,
                employeeId = employeeId,
                homeState = "CA",
                workState = "CA",
                filingStatus = FilingStatus.SINGLE,
                baseCompensation = BaseCompensation.Salaried(
                    annualSalary = annual,
                    frequency = period.frequency,
                ),
            )
            return PaycheckInput(
                paycheckId = BillId("chk-seq-$sequenceInYear"),
                payRunId = BillingCycleId("run-seq"),
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
        }

        // For 100,000 annual and 24 periods:
        // basePerPeriod = floor(100,000 / 24) = 4,166.66 (cents = 416_666)
        // remainderCents = 100,000.00 - 416_666 * 24 = 16 cents
        // -> first 16 periods get +0.01, remaining 8 get the base
        val schedule = PaySchedule.defaultFor(employerId, PayFrequency.SEMI_MONTHLY)
        kotlin.test.assertEquals(24, schedule.periodsPerYear)

        val firstPeriodGross = calculatePaycheckDebug(makeInput(1)).gross.amount
        val sixteenthPeriodGross = calculatePaycheckDebug(makeInput(16)).gross.amount
        val seventeenthPeriodGross = calculatePaycheckDebug(makeInput(17)).gross.amount

        val baseCents = annual.amount / schedule.periodsPerYear
        kotlin.test.assertEquals(baseCents + 1, firstPeriodGross)
        kotlin.test.assertEquals(baseCents + 1, sixteenthPeriodGross)
        kotlin.test.assertEquals(baseCents, seventeenthPeriodGross)
    }

    @Test
    fun `hourly employee with overtime has correct gross`() {
        val employerId = UtilityId("emp-1")
        val employeeId = CustomerId("ee-ot")
        val period = PayPeriod(
            id = "2025-01-W2",
            employerId = employerId,
            dateRange = LocalDateRange(LocalDate.of(2025, 1, 8), LocalDate.of(2025, 1, 14)),
            checkDate = LocalDate.of(2025, 1, 15),
            frequency = PayFrequency.WEEKLY,
        )
        val snapshot = EmployeeSnapshot(
            employerId = employerId,
            employeeId = employeeId,
            homeState = "CA",
            workState = "CA",
            filingStatus = FilingStatus.SINGLE,
            baseCompensation = BaseCompensation.Hourly(hourlyRate = Money(50_00L)), // $50/hr
        )
        val input = PaycheckInput(
            paycheckId = BillId("chk-ot"),
            payRunId = BillingCycleId("run-ot"),
            employerId = employerId,
            employeeId = employeeId,
            period = period,
            employeeSnapshot = snapshot,
            timeSlice = TimeSlice(
                period = period,
                regularHours = 40.0,
                overtimeHours = 5.0,
            ),
            taxContext = TaxContext(),
            priorYtd = YtdSnapshot(year = 2025),
        )

        val result = calculatePaycheckDebug(input)

        // 40 * 50 = 2000, 5 * (50 * 1.5) = 375 -> total 2375
        assertEquals(2_375_00L, result.gross.amount)
        assertEquals(result.gross.amount, result.net.amount)
    }
}
