package com.example.usbilling.payroll.model

import com.example.usbilling.shared.EmployerId
import com.example.usbilling.shared.Money
import java.time.LocalDate

// Basic time and period types

data class LocalDateRange(
    val startInclusive: LocalDate,
    val endInclusive: LocalDate,
)

enum class PayFrequency {
    WEEKLY,
    BIWEEKLY,
    FOUR_WEEKLY,
    SEMI_MONTHLY,
    MONTHLY,
    QUARTERLY,
    ANNUAL,
}

data class PayPeriod(
    val id: String,
    val employerId: EmployerId,
    val dateRange: LocalDateRange,
    val checkDate: LocalDate,
    val frequency: PayFrequency,
    /**
     * 1-based index of this period within the calendar year for the employer's schedule.
     *
     * This is optional for now; when absent, salaried allocation will use the
     * base per-period amount without distributing remainder cents to specific periods.
     */
    val sequenceInYear: Int? = null,
)

// Time and earnings input for the period

data class EarningInput(
    val code: EarningCode,
    val units: Double,
    val rate: Money?, // null if amount is provided directly
    val amount: Money?,
)

data class TimeSlice(
    val period: PayPeriod,
    val regularHours: Double,
    val overtimeHours: Double,
    val otherEarnings: List<EarningInput> = emptyList(),
    /** Optional partial-period proration for salaried employees. */
    val proration: Proration? = null,
    /**
     * When false, the engine will suppress base earnings derived from the employee's
     * base compensation (salary/hourly) and only include explicitly-provided earnings.
     *
     * This is the primary mechanism for modeling off-cycle pay runs (bonuses/commissions)
     * without double-paying base wages.
     */
    val includeBaseEarnings: Boolean = true,
    /**
     * Optional hint for allocating local-taxable wages across multiple localities.
     *
     * Keys should match locality filter codes passed to tax-service (e.g. "DETROIT", "NYC").
     * Values are fractions in [0.0, 1.0]. When absent, local taxes will be split
     * evenly across all selected localities.
     */
    val localityAllocations: Map<String, Double> = emptyMap(),
)
