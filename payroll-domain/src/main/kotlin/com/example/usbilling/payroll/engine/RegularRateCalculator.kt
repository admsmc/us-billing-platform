package com.example.usbilling.payroll.engine

import com.example.usbilling.payroll.model.BaseCompensation
import com.example.usbilling.payroll.model.EarningCategory
import com.example.usbilling.payroll.model.EarningLine
import com.example.usbilling.payroll.model.FlsaExemptStatus
import com.example.usbilling.payroll.model.PaycheckInput
import com.example.usbilling.shared.Money

/**
 * Helpers for computing FLSA-style regular rate effects.
 *
 * The initial focus is very narrow: compute the additional overtime premium
 * owed when an hourly, non-exempt employee receives a nondiscretionary bonus
 * in the same workweek as overtime hours.
 */
object RegularRateCalculator {

    /**
     * Computes the additional overtime premium owed on nondiscretionary bonus
     * pay for a simple hourly+bonus case.
     *
     * This uses the standard FLSA pattern:
     * - Regular rate uplift from bonus = bonus / total hours in the week.
     * - Extra OT premium on bonus = 0.5 * (bonus / totalHours) * overtimeHours.
     *
     * Assumptions / limitations:
     * - Only applies to hourly employees with overtime hours > 0.
     * - Only considers earnings categorized as [EarningCategory.BONUS].
     * - Assumes the pay period aligns with the FLSA workweek.
     */
    fun additionalOvertimePremiumForBonus(input: PaycheckInput, earnings: List<EarningLine>): Money {
        val snapshot = input.employeeSnapshot
        val baseComp = snapshot.baseCompensation
        if (baseComp !is BaseCompensation.Hourly) return Money(0L)
        if (!snapshot.flsaEnterpriseCovered) return Money(0L)
        if (snapshot.flsaExemptStatus != FlsaExemptStatus.NON_EXEMPT) return Money(0L)

        val otHours = input.timeSlice.overtimeHours
        val totalHours = input.timeSlice.regularHours + otHours
        if (otHours <= 0.0 || totalHours <= 0.0) return Money(0L)

        var bonusCents = 0L
        for (line in earnings) {
            if (line.category == EarningCategory.BONUS) {
                bonusCents += line.amount.amount
            }
        }
        if (bonusCents <= 0L) return Money(0L)

        val fractionOt = otHours / totalHours
        val additionalCentsDouble = bonusCents.toDouble() * fractionOt * 0.5
        val additionalCents = additionalCentsDouble.toLong()
        if (additionalCents <= 0L) return Money(0L)

        return Money(additionalCents)
    }
}
