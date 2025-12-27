package com.example.usbilling.time.model

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * A single unit of timekeeping input.
 *
 * This is intentionally coarse-grained (date + hours) to keep the first
 * time-shaping iteration small. Future work can evolve this to punch-level
 * intervals, multiple earning codes, and richer location/job metadata.
 */
data class TimeEntry(
    val date: LocalDate,
    /** Hours worked for the given date. */
    val hours: Double,
    /**
     * Optional tips received/allocated for the given date.
     *
     * These are expressed in cents and intentionally kept as raw amounts.
     * The payroll engine is responsible for determining tax / cash treatment.
     */
    val cashTipsCents: Long = 0L,
    val chargedTipsCents: Long = 0L,
    /** Tip pooling allocation (tips allocated to the employee). */
    val allocatedTipsCents: Long = 0L,
    /**
     * Optional additional earnings amounts for the given date (cents).
     *
     * These are modeled here to support more realistic macrobench scenarios
     * (commission/bonus/reimbursements) without adding a separate earnings service.
     */
    val commissionCents: Long = 0L,
    val bonusCents: Long = 0L,
    val reimbursementNonTaxableCents: Long = 0L,
    /** Optional worksite/job key used for later locality allocation. */
    val worksiteKey: String? = null,
)

data class WorkweekDefinition(
    /** Day the employer's workweek starts on (jurisdiction rules often depend on this). */
    val weekStartsOn: DayOfWeek = DayOfWeek.MONDAY,
)

/** Output buckets suitable for payroll computation. */
data class TimeBuckets(
    val regularHours: Double,
    /** Time-and-a-half overtime hours. */
    val overtimeHours: Double,
    /** Double-time overtime hours. */
    val doubleTimeHours: Double,
)

data class ShapedTime(
    val totals: TimeBuckets,
    /** Optional breakdown by worksite key for later locality allocation. */
    val byWorksite: Map<String, TimeBuckets> = emptyMap(),
)

// ----------------------------
// Tip allocation rules
// ----------------------------

@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "type",
)
@JsonSubTypes(
    JsonSubTypes.Type(value = TipAllocationRuleSet.None::class, name = "NONE"),
    JsonSubTypes.Type(value = TipAllocationRuleSet.SimplePool::class, name = "SIMPLE_POOL"),
)
sealed class TipAllocationRuleSet {
    /** No computed tip allocation. */
    data object None : TipAllocationRuleSet()

    /**
     * Simple worksite-level charged-tip pool allocation.
     *
     * - Compute a pool per worksite as: floor(totalChargedTipsCents * poolPercentOfCharged)
     * - Allocate the pool to tip-eligible employees proportionally to their hours at that worksite.
     */
    data class SimplePool(
        val id: String,
        /** 0.0 - 1.0 */
        val poolPercentOfCharged: Double,
    ) : TipAllocationRuleSet()
}

// ----------------------------
// Overtime rules
// ----------------------------

@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "type",
)
@JsonSubTypes(
    JsonSubTypes.Type(value = OvertimeRuleSet.None::class, name = "NONE"),
    JsonSubTypes.Type(value = OvertimeRuleSet.Simple::class, name = "SIMPLE"),
)
sealed class OvertimeRuleSet {

    /** No overtime shaping. All hours remain regular. */
    data object None : OvertimeRuleSet()

    /**
     * Simple rule-set supporting:
     * - daily thresholds (regular -> OT -> DT)
     * - weekly threshold
     * - 7th-day rule (CA-style)
     */
    data class Simple(
        val id: String,
        val daily: DailyOvertimeRule? = null,
        val weekly: WeeklyOvertimeRule? = null,
        val seventhDay: SeventhDayRule? = null,
    ) : OvertimeRuleSet()
}

data class DailyOvertimeRule(
    /** Hours per day paid at regular rate (e.g., 8). */
    val regularLimitHours: Double,
    /** Hours per day after which double-time starts (e.g., 12). */
    val doubleTimeAfterHours: Double,
)

data class WeeklyOvertimeRule(
    /** Hours per workweek after which overtime starts (e.g., 40). */
    val overtimeAfterHours: Double,
)

/**
 * CA-style 7th consecutive day rule (in a workweek):
 * - first 8 hours: OT
 * - after 8: DT
 */
data class SeventhDayRule(
    val enabled: Boolean = false,
    val firstEightAsOvertime: Boolean = true,
)
