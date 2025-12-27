package com.example.usbilling.payroll.engine

import com.example.usbilling.payroll.model.EarningCategory
import com.example.usbilling.payroll.model.EarningCode
import com.example.usbilling.payroll.model.EarningLine
import com.example.usbilling.payroll.model.FlsaExemptStatus
import com.example.usbilling.payroll.model.LaborStandardsContext
import com.example.usbilling.payroll.model.PaycheckInput
import com.example.usbilling.shared.Money

/**
 * Enforces a simple FLSA tip-credit rule for weekly, non-exempt, tipped
 * hourly employees by returning an updated earnings list with a "make-up"
 * earning line when cash wages plus tips fall below the applicable minimum wage
 * (and, optionally, the tipped cash minimum).
 */
object TipCreditEnforcer {

    fun applyTipCreditMakeup(input: PaycheckInput, laborStandards: LaborStandardsContext?, earnings: List<EarningLine>): List<EarningLine> {
        val snapshot = input.employeeSnapshot
        val baseComp = snapshot.baseCompensation
        if (baseComp !is com.example.usbilling.payroll.model.BaseCompensation.Hourly) return earnings
        if (!snapshot.flsaEnterpriseCovered) return earnings
        if (snapshot.flsaExemptStatus != FlsaExemptStatus.NON_EXEMPT) return earnings
        if (!snapshot.isTippedEmployee) return earnings
        if (laborStandards == null) return earnings

        val minWage = laborStandards.federalMinimumWage
        val tippedCashMin = laborStandards.federalTippedCashMinimum

        val otHours = input.timeSlice.overtimeHours
        val totalHours = input.timeSlice.regularHours + otHours
        if (totalHours <= 0.0) return earnings

        var cashCents = 0L
        var tipCents = 0L
        for (line in earnings) {
            when (line.category) {
                EarningCategory.TIPS -> tipCents += line.amount.amount
                EarningCategory.IMPUTED -> {
                    // excluded from cash gross
                }
                else -> cashCents += line.amount.amount
            }
        }

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
        if (makeUpCents <= 0L) return earnings

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

        val out = ArrayList<EarningLine>(earnings.size + 1)
        out.addAll(earnings)
        out.add(line)
        return out
    }
}
