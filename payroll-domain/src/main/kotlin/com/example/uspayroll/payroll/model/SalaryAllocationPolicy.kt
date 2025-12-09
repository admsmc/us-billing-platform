package com.example.uspayroll.payroll.model

import com.example.uspayroll.shared.Money

/**
 * Policy for allocating an annual salary across pay periods in a schedule.
 *
 * This is intentionally minimal in its first iteration: it mirrors the
 * existing behaviour (simple integer division by periodsPerYear) but moves
 * the responsibility out of the engine so it can evolve independently.
 */
fun interface SalaryAllocationPolicy {
    fun perPeriodAmount(annualSalary: Money, schedule: PaySchedule): Money

    companion object {
        /**
         * Even allocation using integer division.
         *
         * This preserves the current behaviour of the engine: given an annual
         * salary and a schedule with periodsPerYear, compute a constant
         * per-period amount using integer division on the underlying cents.
         */
        val EvenAllocation: SalaryAllocationPolicy = SalaryAllocationPolicy { annualSalary, schedule ->
            RemainderAwareEvenAllocation.compute(annualSalary, schedule).basePerPeriod
        }
    }
}

/**
 * Remainder-aware even allocation.
 *
 * This representation exposes the base per-period amount (floor of annual / periodsPerYear)
 * and how many periods should receive one extra cent so that the total across all periods
 * matches the configured annual salary exactly.
 */
data class RemainderAwareEvenAllocation(
    val basePerPeriod: Money,
    val extraCentsPeriods: Int,
) {
    init {
        require(extraCentsPeriods >= 0) { "extraCentsPeriods must be non-negative" }
    }

    /**
     * Amount for a specific period index in the schedule.
     *
     * By convention, the first [extraCentsPeriods] periods (indices [0, extraCentsPeriods))
     * receive one extra cent; the remaining periods receive the base amount.
     */
    fun amountForPeriod(periodIndexInYear: Int, schedule: PaySchedule): Money {
        require(periodIndexInYear in 0 until schedule.periodsPerYear) {
            "periodIndexInYear=$periodIndexInYear is out of bounds for periodsPerYear=${schedule.periodsPerYear}"
        }
        val extra = if (periodIndexInYear < extraCentsPeriods) 1L else 0L
        return Money(basePerPeriod.amount + extra, basePerPeriod.currency)
    }

    companion object {
        /**
         * Compute a remainder-aware even allocation for the given annual salary and schedule.
         *
         * basePerPeriod = floor(annual / periodsPerYear)
         * extraCentsPeriods = remainder cents after that division. Each of the first
         * [extraCentsPeriods] periods should receive one extra cent so that the total
         * across all periods equals the annual salary.
         */
        fun compute(annualSalary: Money, schedule: PaySchedule): RemainderAwareEvenAllocation {
            require(schedule.periodsPerYear > 0) { "periodsPerYear must be positive" }
            val baseCents = annualSalary.amount / schedule.periodsPerYear
            val totalBase = baseCents * schedule.periodsPerYear
            val remainderCents = annualSalary.amount - totalBase
            require(remainderCents >= 0) { "remainder cents must be non-negative" }
            // remainderCents is strictly less than periodsPerYear when using integer division
            return RemainderAwareEvenAllocation(
                basePerPeriod = Money(baseCents, annualSalary.currency),
                extraCentsPeriods = remainderCents.toInt(),
            )
        }
    }
}
