package com.example.uspayroll.time.engine

import com.example.uspayroll.time.model.DailyOvertimeRule
import com.example.uspayroll.time.model.OvertimeRuleSet
import com.example.uspayroll.time.model.SeventhDayRule
import com.example.uspayroll.time.model.TimeEntry
import com.example.uspayroll.time.model.WeeklyOvertimeRule
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class TimeShaperTest {

    private val caRules = OvertimeRuleSet.Simple(
        id = "CA",
        daily = DailyOvertimeRule(regularLimitHours = 8.0, doubleTimeAfterHours = 12.0),
        weekly = WeeklyOvertimeRule(overtimeAfterHours = 40.0),
        seventhDay = SeventhDayRule(enabled = true),
    )

    @Test
    fun `CA daily overtime example - 10 hours per day for 5 days`() {
        val start = LocalDate.of(2025, 1, 6) // Monday
        val entries = (0 until 5).map { i ->
            TimeEntry(date = start.plusDays(i.toLong()), hours = 10.0)
        }

        val shaped = TimeShaper.shape(entries, caRules)

        assertEquals(40.0, shaped.totals.regularHours)
        assertEquals(10.0, shaped.totals.overtimeHours)
        assertEquals(0.0, shaped.totals.doubleTimeHours)
    }

    @Test
    fun `daily double time - 13 hour day yields 8 regular, 4 OT, 1 DT`() {
        val entries = listOf(TimeEntry(date = LocalDate.of(2025, 1, 6), hours = 13.0))

        val shaped = TimeShaper.shape(entries, caRules)

        assertEquals(8.0, shaped.totals.regularHours)
        assertEquals(4.0, shaped.totals.overtimeHours)
        assertEquals(1.0, shaped.totals.doubleTimeHours)
    }

    @Test
    fun `7th day rule + weekly overtime - 7 days x 8 hours`() {
        val start = LocalDate.of(2025, 1, 6) // Monday
        val entries = (0 until 7).map { i ->
            TimeEntry(date = start.plusDays(i.toLong()), hours = 8.0)
        }

        val shaped = TimeShaper.shape(entries, caRules)

        // After 7th day rule: 48 regular + 8 overtime.
        // Weekly OT requires total 56 -> 16 hours over 40; 8 already overtime, so convert 8 regular -> overtime.
        assertEquals(40.0, shaped.totals.regularHours)
        assertEquals(16.0, shaped.totals.overtimeHours)
        assertEquals(0.0, shaped.totals.doubleTimeHours)
    }
}
