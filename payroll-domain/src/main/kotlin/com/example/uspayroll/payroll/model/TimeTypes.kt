package com.example.uspayroll.payroll.model

import com.example.uspayroll.shared.EmployerId
import com.example.uspayroll.shared.Money
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
    val rate: Money?,  // null if amount is provided directly
    val amount: Money?,
)

data class TimeSlice(
    val period: PayPeriod,
    val regularHours: Double,
    val overtimeHours: Double,
    val otherEarnings: List<EarningInput> = emptyList(),
    /** Optional partial-period proration for salaried employees. */
    val proration: Proration? = null,
)
