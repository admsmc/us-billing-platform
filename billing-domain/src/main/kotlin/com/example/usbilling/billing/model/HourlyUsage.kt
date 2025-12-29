package com.example.usbilling.billing.model

import java.time.Instant
import java.time.LocalDate

/**
 * Hourly usage profile for a single day.
 * Used for time-of-use billing calculations that require granular hourly data.
 *
 * @property date The date this profile applies to
 * @property hourlyReadings List of 24 hourly readings (0-23)
 * @property meterId Meter identifier
 * @property serviceType Type of service
 */
data class HourlyUsageProfile(
    val date: LocalDate,
    val hourlyReadings: List<HourlyReading>,
    val meterId: String,
    val serviceType: ServiceType,
) {
    init {
        require(hourlyReadings.size == 24) { "Hourly profile must contain exactly 24 readings" }
        require(hourlyReadings.map { it.hour }.sorted() == (0..23).toList()) {
            "Hourly readings must cover hours 0-23"
        }
    }

    /**
     * Get usage for a specific hour.
     */
    fun usageForHour(hour: Int): Double {
        require(hour in 0..23) { "Hour must be 0-23" }
        return hourlyReadings.first { it.hour == hour }.usage
    }

    /**
     * Calculate total usage for the day.
     */
    fun totalUsage(): Double = hourlyReadings.sumOf { it.usage }

    /**
     * Get usage during peak hours defined by schedule.
     */
    fun peakUsage(schedule: TouPeriodSchedule): Double = schedule.peakHours.sumOf { range ->
        hourlyReadings.filter { it.hour in range.start..range.endInclusive }
            .sumOf { it.usage }
    }

    /**
     * Get usage during off-peak hours defined by schedule.
     */
    fun offPeakUsage(schedule: TouPeriodSchedule): Double = schedule.offPeakHours.sumOf { range ->
        hourlyReadings.filter { it.hour in range.start..range.endInclusive }
            .sumOf { it.usage }
    }

    /**
     * Get usage during shoulder hours defined by schedule (if applicable).
     */
    fun shoulderUsage(schedule: TouPeriodSchedule): Double = schedule.shoulderHours.sumOf { range ->
        hourlyReadings.filter { it.hour in range.start..range.endInclusive }
            .sumOf { it.usage }
    }
}

/**
 * A single hourly reading.
 *
 * @property hour Hour of day (0-23, where 0 is midnight)
 * @property usage Usage amount for this hour
 * @property timestamp When this reading was recorded
 */
data class HourlyReading(
    val hour: Int,
    val usage: Double,
    val timestamp: Instant,
) {
    init {
        require(hour in 0..23) { "Hour must be 0-23" }
        require(usage >= 0.0) { "Usage cannot be negative" }
    }
}

/**
 * Time-of-use period schedule defining peak, off-peak, and shoulder hours.
 * Supports seasonal variations and holiday schedules.
 *
 * @property peakHours List of hour ranges considered peak
 * @property offPeakHours List of hour ranges considered off-peak
 * @property shoulderHours List of hour ranges considered shoulder (optional)
 * @property weekdaysOnly If true, weekend days use off-peak rates for all hours
 * @property holidays List of dates to treat as off-peak
 */
data class TouPeriodSchedule(
    val peakHours: List<HourRange>,
    val offPeakHours: List<HourRange>,
    val shoulderHours: List<HourRange> = emptyList(),
    val weekdaysOnly: Boolean = true,
    val holidays: List<LocalDate> = emptyList(),
) {
    /**
     * Determine if a given date/hour is peak period.
     */
    fun isPeakPeriod(date: LocalDate, hour: Int): Boolean {
        // Weekends use off-peak if weekdaysOnly is true
        if (weekdaysOnly && (date.dayOfWeek.value == 6 || date.dayOfWeek.value == 7)) {
            return false
        }

        // Holidays use off-peak
        if (holidays.contains(date)) {
            return false
        }

        return peakHours.any { hour in it.start..it.endInclusive }
    }

    /**
     * Determine if a given date/hour is off-peak period.
     */
    fun isOffPeakPeriod(date: LocalDate, hour: Int): Boolean {
        // Weekends are always off-peak if weekdaysOnly is true
        if (weekdaysOnly && (date.dayOfWeek.value == 6 || date.dayOfWeek.value == 7)) {
            return true
        }

        // Holidays are always off-peak
        if (holidays.contains(date)) {
            return true
        }

        return offPeakHours.any { hour in it.start..it.endInclusive }
    }

    /**
     * Determine if a given date/hour is shoulder period.
     */
    fun isShoulderPeriod(date: LocalDate, hour: Int): Boolean {
        // No shoulder periods on weekends or holidays
        if (weekdaysOnly && (date.dayOfWeek.value == 6 || date.dayOfWeek.value == 7)) {
            return false
        }
        if (holidays.contains(date)) {
            return false
        }

        return shoulderHours.any { hour in it.start..it.endInclusive }
    }

    companion object {
        /**
         * Standard residential TOU schedule: peak 2pm-7pm weekdays.
         */
        fun standardResidential(): TouPeriodSchedule = TouPeriodSchedule(
            peakHours = listOf(HourRange(14, 18)), // 2pm-7pm
            offPeakHours = listOf(
                HourRange(0, 13), // midnight-2pm
                HourRange(19, 23), // 7pm-midnight
            ),
            weekdaysOnly = true,
        )

        /**
         * Standard commercial TOU schedule: peak 8am-9pm weekdays.
         */
        fun standardCommercial(): TouPeriodSchedule = TouPeriodSchedule(
            peakHours = listOf(HourRange(8, 20)), // 8am-9pm
            offPeakHours = listOf(
                HourRange(0, 7), // midnight-8am
                HourRange(21, 23), // 9pm-midnight
            ),
            weekdaysOnly = true,
        )

        /**
         * Three-period TOU schedule with shoulder periods.
         */
        fun threePeriod(): TouPeriodSchedule = TouPeriodSchedule(
            peakHours = listOf(HourRange(14, 19)), // 2pm-8pm
            offPeakHours = listOf(
                HourRange(0, 6), // midnight-7am
                HourRange(23, 23), // 11pm-midnight
            ),
            shoulderHours = listOf(
                HourRange(7, 13), // 7am-2pm
                HourRange(20, 22), // 8pm-11pm
            ),
            weekdaysOnly = true,
        )
    }
}

/**
 * Seasonal TOU schedule with different schedules for summer and winter.
 *
 * @property summerSchedule Schedule for summer months (typically higher peak rates)
 * @property winterSchedule Schedule for winter months
 * @property summerMonths Months considered summer (default: June-September)
 */
data class SeasonalTouSchedule(
    val summerSchedule: TouPeriodSchedule,
    val winterSchedule: TouPeriodSchedule,
    val summerMonths: Set<Int> = setOf(6, 7, 8, 9), // June-September
) {
    /**
     * Get the appropriate schedule for a given date.
     */
    fun scheduleForDate(date: LocalDate): TouPeriodSchedule = if (summerMonths.contains(date.monthValue)) {
        summerSchedule
    } else {
        winterSchedule
    }
}

/**
 * Hour range (inclusive).
 *
 * @property start Start hour (0-23)
 * @property endInclusive End hour (0-23), inclusive
 */
data class HourRange(
    val start: Int,
    val endInclusive: Int,
) {
    init {
        require(start in 0..23) { "Start hour must be 0-23" }
        require(endInclusive in 0..23) { "End hour must be 0-23" }
        require(start <= endInclusive) { "Start hour must be <= end hour" }
    }

    /**
     * Check if an hour falls within this range.
     */
    operator fun contains(hour: Int): Boolean = hour in start..endInclusive

    /**
     * Get number of hours in this range.
     */
    fun hourCount(): Int = endInclusive - start + 1
}
