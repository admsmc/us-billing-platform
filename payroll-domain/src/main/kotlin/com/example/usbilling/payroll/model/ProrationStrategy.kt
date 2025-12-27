package com.example.usbilling.payroll.model

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Strategy for computing an implicit proration for a pay period based on
 * employee lifecycle dates (hire/termination) and the period's date range.
 */
fun interface ProrationStrategy {
    fun computeProration(period: PayPeriod, hireDate: LocalDate?, terminationDate: LocalDate?): Proration?

    companion object {
        /**
         * Calendar-day based proration:
         * - totalDays = inclusive days in the pay period date range
         * - workedDays = inclusive days between max(period.start, hireDate) and
         *   min(period.end, terminationDate) when these overlap
         * - If hire/termination do not intersect the period, returns a 0.0 proration.
         * - If workedDays == totalDays, returns null (no proration needed).
         * - Otherwise returns Proration(workedDays / totalDays).
         */
        val CalendarDays: ProrationStrategy = ProrationStrategy { period, hireDate, terminationDate ->
            val periodStart = period.dateRange.startInclusive
            val periodEnd = period.dateRange.endInclusive

            if (hireDate == null && terminationDate == null) {
                // No lifecycle dates supplied -> leave proration to explicit overrides
                return@ProrationStrategy null
            }

            val effectiveStart = when {
                hireDate != null && hireDate.isAfter(periodStart) -> hireDate
                else -> periodStart
            }
            val effectiveEnd = when {
                terminationDate != null && terminationDate.isBefore(periodEnd) -> terminationDate
                else -> periodEnd
            }

            if (effectiveEnd.isBefore(periodStart) || effectiveStart.isAfter(periodEnd)) {
                // Lifecycle window does not intersect this period
                return@ProrationStrategy Proration(0.0)
            }

            val totalDays = ChronoUnit.DAYS.between(periodStart, periodEnd) + 1
            val workedDays = ChronoUnit.DAYS.between(effectiveStart, effectiveEnd) + 1

            if (workedDays <= 0L) {
                return@ProrationStrategy Proration(0.0)
            }
            if (workedDays >= totalDays) {
                // Fully worked; no proration needed
                return@ProrationStrategy null
            }

            val fraction = workedDays.toDouble() / totalDays.toDouble()
            Proration(fraction)
        }

        /**
         * Workday-based proration:
         * - Counts only Monday–Friday as workdays within the period.
         * - Otherwise follows the same overlap rules as CalendarDays.
         */
        val Workdays: ProrationStrategy = ProrationStrategy { period, hireDate, terminationDate ->
            val periodStart = period.dateRange.startInclusive
            val periodEnd = period.dateRange.endInclusive

            if (hireDate == null && terminationDate == null) {
                return@ProrationStrategy null
            }

            val effectiveStart = when {
                hireDate != null && hireDate.isAfter(periodStart) -> hireDate
                else -> periodStart
            }
            val effectiveEnd = when {
                terminationDate != null && terminationDate.isBefore(periodEnd) -> terminationDate
                else -> periodEnd
            }

            if (effectiveEnd.isBefore(periodStart) || effectiveStart.isAfter(periodEnd)) {
                return@ProrationStrategy Proration(0.0)
            }

            fun isWorkday(d: LocalDate): Boolean {
                val dow = d.dayOfWeek
                return dow.value in 1..5 // MON-FRI
            }

            fun countWorkdays(start: LocalDate, endInclusive: LocalDate): Long {
                var d = start
                var count = 0L
                while (!d.isAfter(endInclusive)) {
                    if (isWorkday(d)) count++
                    d = d.plusDays(1)
                }
                return count
            }

            val totalWorkdays = countWorkdays(periodStart, periodEnd)
            val workedWorkdays = countWorkdays(effectiveStart, effectiveEnd)

            if (workedWorkdays <= 0L) {
                return@ProrationStrategy Proration(0.0)
            }
            if (workedWorkdays >= totalWorkdays) {
                return@ProrationStrategy null
            }

            val fraction = workedWorkdays.toDouble() / totalWorkdays.toDouble()
            Proration(fraction)
        }

        /**
         * Contract-style 30-day month proration.
         *
         * Interprets the period as having 30 days regardless of the actual
         * calendar length, using a 30-day denominator as is common in some
         * employment contracts.
         */
        val ThirtyDayMonth: ProrationStrategy = ProrationStrategy { period, hireDate, terminationDate ->
            val periodStart = period.dateRange.startInclusive
            val periodEnd = period.dateRange.endInclusive

            if (hireDate == null && terminationDate == null) {
                return@ProrationStrategy null
            }

            val effectiveStart = when {
                hireDate != null && hireDate.isAfter(periodStart) -> hireDate
                else -> periodStart
            }
            val effectiveEnd = when {
                terminationDate != null && terminationDate.isBefore(periodEnd) -> terminationDate
                else -> periodEnd
            }

            if (effectiveEnd.isBefore(periodStart) || effectiveStart.isAfter(periodEnd)) {
                return@ProrationStrategy Proration(0.0)
            }

            val workedDays = ChronoUnit.DAYS.between(effectiveStart, effectiveEnd) + 1
            if (workedDays <= 0L) {
                return@ProrationStrategy Proration(0.0)
            }

            val totalDays = 30.0
            val fraction = (workedDays.toDouble() / totalDays).coerceAtMost(1.0)
            if (fraction >= 1.0) {
                null
            } else {
                Proration(fraction)
            }
        }
    }
}
