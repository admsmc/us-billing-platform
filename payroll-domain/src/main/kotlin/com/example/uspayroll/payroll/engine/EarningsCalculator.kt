package com.example.uspayroll.payroll.engine

import com.example.uspayroll.payroll.model.*
import com.example.uspayroll.payroll.model.config.EarningConfigRepository
import com.example.uspayroll.shared.Money

/**
 * Simple earnings calculator; will be extended with overtime and other earnings.
 */
object EarningsCalculator {
    fun computeEarnings(input: PaycheckInput, earningConfig: EarningConfigRepository? = null, overtimePolicy: OvertimePolicy = OvertimePolicy.Default): List<EarningLine> {
        val base = input.employeeSnapshot.baseCompensation
        val period = input.period
        val slice = input.timeSlice

        val lines = mutableListOf<EarningLine>()

        when (base) {
            is BaseCompensation.Salaried -> {
                // Derive an effective pay schedule, preferring an explicit schedule if provided,
                // otherwise falling back to a default based on the period frequency.
                val schedule = input.paySchedule
                    ?: PaySchedule.defaultFor(input.employerId, period.frequency)

                // Delegate salaried allocation (including optional proration) to a policy
                // so the strategy can evolve independently of the engine.
                val perPeriodMoney = SalaryProrationPolicy.Default
                    .amountForPeriod(
                        annualSalary = base.annualSalary,
                        schedule = schedule,
                        period = period,
                        employeeSnapshot = input.employeeSnapshot,
                        explicitProration = slice.proration,
                    )

                val defaultCode = EarningCode("BASE")
                val def = earningConfig?.findByEmployerAndCode(input.employerId, defaultCode)
                val code = def?.code ?: defaultCode
                val category = def?.category ?: EarningCategory.REGULAR
                val description = def?.displayName ?: "Base salary"

                val baseLine = EarningLine(
                    code = code,
                    category = category,
                    description = description,
                    units = 1.0,
                    rate = perPeriodMoney,
                    amount = perPeriodMoney,
                )
                lines += baseLine
            }
            is BaseCompensation.Hourly -> {
                val regularHours = slice.regularHours

                val defaultCode = EarningCode("HOURLY")
                val def = earningConfig?.findByEmployerAndCode(input.employerId, defaultCode)
                val code = def?.code ?: defaultCode
                val category = def?.category ?: EarningCategory.REGULAR
                val description = def?.displayName ?: "Hourly wages"

                val regularCents = (base.hourlyRate.amount * regularHours).toLong()
                val regularLine = EarningLine(
                    code = code,
                    category = category,
                    description = description,
                    units = regularHours,
                    rate = base.hourlyRate,
                    amount = Money(regularCents),
                )
                lines += regularLine

                // Delegate overtime behaviour to the policy
                val overtimeLines = overtimePolicy.computeOvertimeLines(
                    employerId = input.employerId,
                    baseComp = base,
                    timeSlice = slice,
                    earningConfig = earningConfig,
                )
                lines += overtimeLines
            }
        }

        // Process any additional earnings explicitly provided on the TimeSlice
        slice.otherEarnings.forEach { inputEarning ->
            val def = earningConfig?.findByEmployerAndCode(input.employerId, inputEarning.code)
            val category = def?.category ?: EarningCategory.BONUS
            val description = def?.displayName ?: inputEarning.code.value

            val amount: Money = when {
                inputEarning.amount != null -> inputEarning.amount
                inputEarning.rate != null -> {
                    val cents = (inputEarning.rate.amount * inputEarning.units).toLong()
                    Money(cents)
                }
                def?.defaultRate != null -> {
                    val cents = (def.defaultRate.amount * inputEarning.units).toLong()
                    Money(cents)
                }
                else -> Money(0L)
            }

            val rate: Money? = when {
                inputEarning.rate != null -> inputEarning.rate
                inputEarning.amount != null && inputEarning.units > 0.0 -> {
                    val unitRateCents = (inputEarning.amount.amount / inputEarning.units).toLong()
                    Money(unitRateCents)
                }
                else -> def?.defaultRate
            }

            val line = EarningLine(
                code = inputEarning.code,
                category = category,
                description = description,
                units = inputEarning.units,
                rate = rate,
                amount = amount,
            )
            lines += line
        }

        return lines
    }
}
