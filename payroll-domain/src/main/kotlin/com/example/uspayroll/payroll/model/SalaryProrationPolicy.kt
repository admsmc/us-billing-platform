package com.example.uspayroll.payroll.model

import com.example.uspayroll.shared.Money

/**
 * Policy for computing the salaried amount for a specific pay period,
 * given the annual salary, pay schedule, period metadata, and optional
 * partial-period proration.
 */
fun interface SalaryProrationPolicy {
    fun amountForPeriod(annualSalary: Money, schedule: PaySchedule, period: PayPeriod, employeeSnapshot: EmployeeSnapshot, explicitProration: Proration?): Money

    companion object {
        /**
         * Default implementation using remainder-aware even allocation,
         * with an optional fractional proration.
         *
         * - First compute the full-period amount using RemainderAwareEvenAllocation
         *   and the period's sequenceInYear when present.
         * - If a proration is provided, multiply the full-period amount by the
         *   proration fraction and round down to whole cents.
         */
        val Default: SalaryProrationPolicy = SalaryProrationPolicy { annualSalary, schedule, period, employeeSnapshot, explicitProration ->
            val allocation = RemainderAwareEvenAllocation.compute(annualSalary, schedule)

            val baseCentsForPeriod: Long = period.sequenceInYear
                ?.let { seq ->
                    val zeroBasedIndex = seq - 1
                    if (zeroBasedIndex in 0 until schedule.periodsPerYear) {
                        allocation.amountForPeriod(zeroBasedIndex, schedule).amount
                    } else {
                        allocation.basePerPeriod.amount
                    }
                }
                ?: allocation.basePerPeriod.amount

            // Prefer an explicit proration on the time slice; otherwise fall back to
            // a strategy-derived proration based on hire/termination lifecycle dates.
            val strategyProration = ProrationStrategy.CalendarDays
                .computeProration(period, employeeSnapshot.hireDate, employeeSnapshot.terminationDate)

            val proration = explicitProration ?: strategyProration

            if (proration == null) {
                Money(baseCentsForPeriod, annualSalary.currency)
            } else {
                val proratedCents = (baseCentsForPeriod * proration.fraction).toLong()
                Money(proratedCents, annualSalary.currency)
            }
        }
    }
}
