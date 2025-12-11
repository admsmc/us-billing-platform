package com.example.uspayroll.payroll.engine.pub15t

import com.example.uspayroll.payroll.model.PayFrequency
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NraAdjustmentTest {

    @Test
    fun `modern W-4 NRAs use official Pub 15-T 2025 Table 2 amounts`() {
        val weekly = NraAdjustment.extraWagesForNra(
            frequency = PayFrequency.WEEKLY,
            w4Version = W4Version.MODERN_2020_PLUS,
            firstPaidBefore2020 = false,
        )
        val annual = NraAdjustment.extraWagesForNra(
            frequency = PayFrequency.ANNUAL,
            w4Version = W4Version.MODERN_2020_PLUS,
            firstPaidBefore2020 = false,
        )

        assertEquals(288_50L, weekly.amount)
        assertEquals(15_000_00L, annual.amount)
    }

    @Test
    fun `legacy NRAs differ based on firstPaidBefore2020 flag`() {
        val weeklyBefore = NraAdjustment.extraWagesForNra(
            frequency = PayFrequency.WEEKLY,
            w4Version = W4Version.LEGACY_PRE_2020,
            firstPaidBefore2020 = true,
        )
        val weeklyAfter = NraAdjustment.extraWagesForNra(
            frequency = PayFrequency.WEEKLY,
            w4Version = W4Version.LEGACY_PRE_2020,
            firstPaidBefore2020 = false,
        )

        assertTrue(weeklyBefore.amount > 0L)
        assertTrue(weeklyAfter.amount > 0L)
        // Table 1 (pre-2020 first paid before 2020) weekly amount should be
        // smaller than Table 2 (2020+ / first paid 2020 or later) for 2025.
        assertTrue(weeklyBefore.amount < weeklyAfter.amount)
    }

    @Test
    fun `legacy extra wages scale roughly with frequency`() {
        val weekly = NraAdjustment.extraWagesForNra(
            frequency = PayFrequency.WEEKLY,
            w4Version = W4Version.LEGACY_PRE_2020,
            firstPaidBefore2020 = true,
        )
        val biweekly = NraAdjustment.extraWagesForNra(
            frequency = PayFrequency.BIWEEKLY,
            w4Version = W4Version.LEGACY_PRE_2020,
            firstPaidBefore2020 = true,
        )

        assertEquals(205_80L, weekly.amount)
        assertEquals(411_50L, biweekly.amount)
    }
}
