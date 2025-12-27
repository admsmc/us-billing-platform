package com.example.usbilling.payroll.model

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class ProrationStrategyTest {

    private fun period(start: LocalDate, end: LocalDate): PayPeriod = PayPeriod(
        id = "P",
        employerId = com.example.usbilling.shared.UtilityId("E"),
        dateRange = LocalDateRange(start, end),
        checkDate = end,
        frequency = PayFrequency.SEMI_MONTHLY,
    )

    @Test
    fun `calendar days proration with mid-period termination`() {
        val p = period(LocalDate.of(2025, 2, 1), LocalDate.of(2025, 2, 15))
        val term = LocalDate.of(2025, 2, 10)

        val proration = ProrationStrategy.CalendarDays.computeProration(p, hireDate = null, terminationDate = term)!!

        // 15 days total, 10 days worked (1st to 10th)
        val expected = 10.0 / 15.0
        assertEquals(expected, proration.fraction)
    }

    @Test
    fun `calendar days proration with termination at period end yields no proration`() {
        val p = period(LocalDate.of(2025, 2, 1), LocalDate.of(2025, 2, 15))
        val term = LocalDate.of(2025, 2, 15)

        val proration = ProrationStrategy.CalendarDays.computeProration(p, hireDate = null, terminationDate = term)

        // Fully worked; no proration object
        assertEquals(null, proration)
    }

    @Test
    fun `workday proration excludes weekends`() {
        // Period spanning one full week: Mon-Sun
        val p = period(LocalDate.of(2025, 3, 3), LocalDate.of(2025, 3, 9)) // 3rd (Mon) to 9th (Sun)
        val term = LocalDate.of(2025, 3, 5) // Wednesday

        val proration = ProrationStrategy.Workdays.computeProration(p, hireDate = null, terminationDate = term)!!

        // Workdays in period: Mon-Fri => 5 days
        // Worked workdays: Mon-Wed => 3 days
        val expected = 3.0 / 5.0
        assertEquals(expected, proration.fraction)
    }

    @Test
    fun `thirty day month proration uses 30-day denominator`() {
        val p = period(LocalDate.of(2025, 4, 1), LocalDate.of(2025, 4, 30))
        val term = LocalDate.of(2025, 4, 15)

        val proration = ProrationStrategy.ThirtyDayMonth.computeProration(p, hireDate = null, terminationDate = term)!!

        // Worked days 1-15 inclusive => 15, denominator fixed at 30
        val expected = 15.0 / 30.0
        assertEquals(expected, proration.fraction)
    }
}
