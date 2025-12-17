package com.example.uspayroll.time.model

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
