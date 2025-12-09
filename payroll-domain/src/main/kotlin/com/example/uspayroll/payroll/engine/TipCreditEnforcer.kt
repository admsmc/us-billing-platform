package com.example.uspayroll.payroll.engine

import com.example.uspayroll.payroll.model.EarningCategory
import com.example.uspayroll.payroll.model.EarningCode
import com.example.uspayroll.payroll.model.EarningLine
import com.example.uspayroll.payroll.model.FlsaExemptStatus
import com.example.uspayroll.payroll.model.LaborStandardsContext
import com.example.uspayroll.payroll.model.PaycheckInput
import com.example.uspayroll.shared.Money

/**
 * Enforces a simple FLSA tip-credit rule for weekly, non-exempt, tipped
 * hourly employees by adding a "make-up" earning line when cash wages plus
 * tips fall below the applicable minimum wage (and, optionally, the tipped
 * cash minimum).
 */
object TipCreditEnforcer {

    fun applyTipCreditMakeup(
        input: PaycheckInput,
        laborStandards: LaborStandardsContext?,
        earnings: MutableList<EarningLine>,
    ) {
        val snapshot = input.employeeSnapshot
        val baseComp = snapshot.baseCompensation
        if (baseComp !is com.example.uspayroll.payroll.model.BaseCompensation.Hourly) return
        if (!snapshot.flsaEnterpriseCovered) return
        if (snapshot.flsaExemptStatus != FlsaExemptStatus.NON_EXEMPT) return
        if (!snapshot.isTippedEmployee) return
        if (laborStandards == null) return

        val minWage = laborStandards.federalMinimumWage
        val tippedCashMin = laborStandards.federalTippedCashMinimum

        val otHours = input.timeSlice.overtimeHours
        val totalHours = input.timeSlice.regularHours + otHours
        if (totalHours <= 0.0) return

        val cashCents: Long = earnings
            .filter { it.category != EarningCategory.TIPS && it.category != EarningCategory.IMPUTED }
            .fold(0L) { acc, line -> acc + line.amount.amount }
        val tipCents: Long = earnings
            .filter { it.category == EarningCategory.TIPS }
            .fold(0L) { acc, line -> acc + line.amount.amount }

        // Overall minimum wage deficiency
        val requiredTotalCents = (minWage.amount * totalHours).toLong()
        val actualTotalCents = cashCents + tipCents
        val totalDeficiency = (requiredTotalCents - actualTotalCents).coerceAtLeast(0L)

        // Optional cash-floor deficiency for tipped employees
        val cashFloorDeficiency: Long = if (tippedCashMin != null) {
            val requiredCashCents = (tippedCashMin.amount * totalHours).toLong()
            (requiredCashCents - cashCents).coerceAtLeast(0L)
        } else {
            0L
        }

        val makeUpCents = maxOf(totalDeficiency, cashFloorDeficiency)
        if (makeUpCents <= 0L) return

        val makeUpMoney = Money(makeUpCents)
        val rate = Money((makeUpCents / totalHours).toLong())

        val line = EarningLine(
            code = EarningCode("TIP_MAKEUP"),
            category = EarningCategory.REGULAR,
            description = "Tip credit make-up to minimum wage",
            units = totalHours,
            rate = rate,
            amount = makeUpMoney,
        )
        earnings += line
    }
}