package com.example.usbilling.payroll.engine.pub15t

import com.example.usbilling.payroll.model.PayFrequency
import com.example.usbilling.shared.Money

/**
 * Helpers for computing additional wages to use for nonresident alien (NRA)
 * withholding per IRS Pub. 15-T.
 *
 * The concrete dollar amounts here are placeholders and should be replaced with
 * the official table values for the relevant tax year when available. The
 * structure is kept isolated so that numerical updates do not affect engine
 * code.
 */
object NraAdjustment {

    /**
     * Return the additional wage amount to add to per-period FederalTaxable
     * wages for a nonresident alien employee before applying Pub. 15-T.
     *
     * @param frequency            pay frequency of the period
     * @param w4Version            which W-4 regime applies
     * @param firstPaidBefore2020  whether wages under this W-4 started before
     *                             2020 (used to distinguish certain table
     *                             variants in Pub. 15-T)
     */
    fun extraWagesForNra(frequency: PayFrequency, w4Version: W4Version, firstPaidBefore2020: Boolean): Money {
        // Pub. 15-T 2025 "Withholding Adjustment for Nonresident Alien
        // Employees" defines two tables:
        // - Table 1: pre-2020 Form W-4, first paid before 2020.
        // - Table 2: 2020+ Form W-4 or first paid 2020 or later.
        val cents = when (w4Version) {
            W4Version.MODERN_2020_PLUS -> table2Amount(frequency)
            W4Version.LEGACY_PRE_2020 -> if (firstPaidBefore2020) {
                table1Amount(frequency)
            } else {
                table2Amount(frequency)
            }
        }
        return Money(cents)
    }

    private fun table1Amount(frequency: PayFrequency): Long = when (frequency) {
        // Table 1 (Pub. 15-T 2025): pre-2020 W-4, first paid before 2020.
        PayFrequency.WEEKLY -> 205_80L
        PayFrequency.BIWEEKLY -> 411_50L
        PayFrequency.FOUR_WEEKLY -> 2_675_00L // use quarterly amount for four-weekly
        PayFrequency.SEMI_MONTHLY -> 445_80L
        PayFrequency.MONTHLY -> 891_70L
        PayFrequency.QUARTERLY -> 2_675_00L
        PayFrequency.ANNUAL -> 10_700_00L
    }

    private fun table2Amount(frequency: PayFrequency): Long = when (frequency) {
        // Table 2 (Pub. 15-T 2025): 2020+ W-4 or first paid 2020 or later.
        PayFrequency.WEEKLY -> 288_50L
        PayFrequency.BIWEEKLY -> 576_90L
        PayFrequency.FOUR_WEEKLY -> 3_750_00L // use quarterly amount for four-weekly
        PayFrequency.SEMI_MONTHLY -> 625_00L
        PayFrequency.MONTHLY -> 1_250_00L
        PayFrequency.QUARTERLY -> 3_750_00L
        PayFrequency.ANNUAL -> 15_000_00L
    }
}
