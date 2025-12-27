package com.example.usbilling.payroll.model

import com.example.usbilling.shared.EmployerId

/**
 * Pay schedule metadata for an employer.
 *
 * This is intentionally minimal for now: it captures the frequency and
 * an explicit periodsPerYear count so salaried allocation can be policy-driven
 * instead of hard-coded in the engine.
 */
data class PaySchedule(
    val employerId: EmployerId,
    val frequency: PayFrequency,
    val periodsPerYear: Int,
) {
    init {
        require(periodsPerYear > 0) { "periodsPerYear must be positive, was $periodsPerYear" }
    }

    companion object {
        fun defaultFor(employerId: EmployerId, frequency: PayFrequency): PaySchedule = PaySchedule(
            employerId = employerId,
            frequency = frequency,
            periodsPerYear = periodsPerYearFor(frequency),
        )

        fun periodsPerYearFor(frequency: PayFrequency): Int = when (frequency) {
            PayFrequency.WEEKLY -> 52
            PayFrequency.BIWEEKLY -> 26
            PayFrequency.FOUR_WEEKLY -> 13
            PayFrequency.SEMI_MONTHLY -> 24
            PayFrequency.MONTHLY -> 12
            PayFrequency.QUARTERLY -> 4
            PayFrequency.ANNUAL -> 1
        }
    }
}
