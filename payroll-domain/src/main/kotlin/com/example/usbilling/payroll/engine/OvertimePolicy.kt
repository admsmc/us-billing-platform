package com.example.usbilling.payroll.engine

import com.example.usbilling.payroll.model.BaseCompensation
import com.example.usbilling.payroll.model.EarningCategory
import com.example.usbilling.payroll.model.EarningCode
import com.example.usbilling.payroll.model.EarningLine
import com.example.usbilling.payroll.model.TimeSlice
import com.example.usbilling.payroll.model.config.EarningConfigRepository
import com.example.usbilling.shared.UtilityId
import com.example.usbilling.shared.Money

/**
 * Policy for deriving overtime earning lines for an hourly employee.
 *
 * Regular earnings for hourly employees are still computed directly in
 * EarningsCalculator; this policy is focused on overtime behaviour so
 * jurisdictions and employer preferences can be swapped without touching
 * the core engine.
 */
fun interface OvertimePolicy {
    fun computeOvertimeLines(employerId: UtilityId, baseComp: BaseCompensation.Hourly, timeSlice: TimeSlice, earningConfig: EarningConfigRepository?): List<EarningLine>

    companion object {
        /**
         * Default overtime policy:
         * - Uses the employer's earning definition for the base HOURLY code
         *   to look up an overtimeMultiplier, defaulting to 1.5 if absent.
         * - Applies that multiplier to the base hourly rate for all overtimeHours
         *   in the TimeSlice.
         */
        val Default: OvertimePolicy = OvertimePolicy { employerId, baseComp, timeSlice, earningConfig ->
            val overtimeHours = timeSlice.overtimeHours
            if (overtimeHours <= 0.0) return@OvertimePolicy emptyList()

            val defaultCode = EarningCode("HOURLY")
            val def = earningConfig?.findByEmployerAndCode(employerId, defaultCode)
            val multiplier = def?.overtimeMultiplier ?: 1.5

            val overtimeRateCents = (baseComp.hourlyRate.amount * multiplier).toLong()
            val overtimeRate = Money(overtimeRateCents)
            val overtimeCents = (overtimeRate.amount * overtimeHours).toLong()

            val overtimeCode = EarningCode("HOURLY_OT")
            val overtimeLine = EarningLine(
                code = overtimeCode,
                category = EarningCategory.OVERTIME,
                description = "Overtime wages",
                units = overtimeHours,
                rate = overtimeRate,
                amount = Money(overtimeCents),
            )

            listOf(overtimeLine)
        }
    }
}
