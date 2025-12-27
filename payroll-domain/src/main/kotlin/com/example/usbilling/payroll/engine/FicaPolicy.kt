package com.example.usbilling.payroll.engine

/**
 * FICA-related policy helpers, including computation of Additional Medicare tax
 * for a single payroll period based on prior-year-to-date Medicare wages and
 * current-period Medicare wages for a given employer.
 */
object FicaPolicy {

    /**
     * Compute Additional Medicare tax for a single payroll period.
     *
     * IRS rule (summarized): An employer must withhold Additional Medicare
     * Tax (0.9%) on wages it pays to an employee in excess of
     * $200,000 in a calendar year, regardless of filing status or wages
     * from other employers.
     *
     * This helper takes the prior YTD Medicare wages paid by this employer
     * and the current-period Medicare wages, and returns the number of
     * cents subject to the Additional Medicare rate of [rate].
     */
    fun additionalMedicareForPeriod(priorMedicareYtdCents: Long, currentMedicareCents: Long, thresholdCents: Long = 200_000_00L, rate: Double = 0.009): Long {
        if (currentMedicareCents <= 0L) return 0L

        val totalAfterThisPeriod = priorMedicareYtdCents + currentMedicareCents

        // If total wages including this period do not exceed the threshold,
        // no Additional Medicare is due.
        if (totalAfterThisPeriod <= thresholdCents) {
            return 0L
        }

        // Wages above the threshold during this period.
        val excessOverThreshold = totalAfterThisPeriod - thresholdCents

        // The amount of this period's wages that are actually above the
        // threshold is the lesser of the excess and the current period's
        // Medicare wages.
        val excessInThisPeriod = minOf(excessOverThreshold, currentMedicareCents)

        if (excessInThisPeriod <= 0L) return 0L

        return (excessInThisPeriod * rate).toLong()
    }
}
