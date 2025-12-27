package com.example.usbilling.time.engine

import com.example.usbilling.time.model.DailyOvertimeRule
import com.example.usbilling.time.model.OvertimeRuleSet
import com.example.usbilling.time.model.ShapedTime
import com.example.usbilling.time.model.TimeBuckets
import com.example.usbilling.time.model.TimeEntry
import com.example.usbilling.time.model.WorkweekDefinition
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Deterministic time shaping: raw per-day hours -> (regular, OT, DT) buckets.
 *
 * This is intentionally a pure function so it can be reused by a future
 * time service, batch jobs, tests, and offline tooling.
 */
object TimeShaper {

    fun shape(entries: List<TimeEntry>, ruleSet: OvertimeRuleSet, workweek: WorkweekDefinition = WorkweekDefinition()): ShapedTime {
        val byKey = entries.groupBy { it.worksiteKey ?: "__default__" }

        val shapedByKey = byKey.mapValues { (_, es) ->
            shapeOne(es, ruleSet, workweek)
        }

        val totals = shapedByKey.values.fold(TimeBuckets(0.0, 0.0, 0.0)) { acc, b ->
            TimeBuckets(
                regularHours = acc.regularHours + b.regularHours,
                overtimeHours = acc.overtimeHours + b.overtimeHours,
                doubleTimeHours = acc.doubleTimeHours + b.doubleTimeHours,
            )
        }

        val byWorksite = shapedByKey
            .filterKeys { it != "__default__" }

        return ShapedTime(totals = totals, byWorksite = byWorksite)
    }

    private fun shapeOne(entries: List<TimeEntry>, ruleSet: OvertimeRuleSet, workweek: WorkweekDefinition): TimeBuckets {
        val byDate: Map<LocalDate, Double> = entries
            .groupBy { it.date }
            .mapValues { (_, es) -> es.sumOf { it.hours } }

        if (byDate.isEmpty()) return TimeBuckets(0.0, 0.0, 0.0)

        return when (ruleSet) {
            is OvertimeRuleSet.None -> {
                val total = byDate.values.sum()
                TimeBuckets(regularHours = total, overtimeHours = 0.0, doubleTimeHours = 0.0)
            }

            is OvertimeRuleSet.Simple -> shapeSimple(byDate, ruleSet, workweek)
        }
    }

    private fun shapeSimple(hoursByDate: Map<LocalDate, Double>, rules: OvertimeRuleSet.Simple, workweek: WorkweekDefinition): TimeBuckets {
        val orderedDates = hoursByDate.keys.sorted()

        // First pass: daily rules (including optional 7th day).
        var regular = 0.0
        var overtime = 0.0
        var doubleTime = 0.0

        // Pre-compute which dates are the 7th consecutive day in the workweek.
        val seventhDayDates = if (rules.seventhDay?.enabled == true) {
            findSeventhDayDates(hoursByDate.keys, workweek.weekStartsOn)
        } else {
            emptySet()
        }

        for (d in orderedDates) {
            val hours = hoursByDate[d] ?: 0.0
            if (hours <= 0.0) {
                continue
            }

            if (d in seventhDayDates) {
                val (r, ot, dt) = allocateSeventhDay(hours)
                regular += r
                overtime += ot
                doubleTime += dt
            } else {
                val dailyRule = rules.daily
                if (dailyRule == null) {
                    regular += hours
                } else {
                    val (r, ot, dt) = allocateDaily(hours, dailyRule)
                    regular += r
                    overtime += ot
                    doubleTime += dt
                }
            }
        }

        // Second pass: weekly rule (convert some remaining regular hours to overtime).
        val weeklyRule = rules.weekly
        if (weeklyRule != null) {
            val totalHours = hoursByDate.values.sum()

            // Hours above the weekly threshold that are *not already premium*.
            // This is the key to avoiding double-counting when daily OT already
            // accounts for the workweek excess.
            val weeklyPremiumNeeded = (totalHours - weeklyRule.overtimeAfterHours - overtime - doubleTime).coerceAtLeast(0.0)

            val convert = minOf(regular, weeklyPremiumNeeded)
            regular -= convert
            overtime += convert
        }

        return TimeBuckets(
            regularHours = regular,
            overtimeHours = overtime,
            doubleTimeHours = doubleTime,
        )
    }

    private fun allocateDaily(hours: Double, rule: DailyOvertimeRule): Triple<Double, Double, Double> {
        val reg = minOf(hours, rule.regularLimitHours)
        val ot = minOf((hours - rule.regularLimitHours).coerceAtLeast(0.0), (rule.doubleTimeAfterHours - rule.regularLimitHours).coerceAtLeast(0.0))
        val dt = (hours - rule.doubleTimeAfterHours).coerceAtLeast(0.0)
        return Triple(reg, ot, dt)
    }

    private fun allocateSeventhDay(hours: Double): Triple<Double, Double, Double> {
        // CA-style: first 8 hours are overtime, then double-time.
        val ot = minOf(hours, 8.0)
        val dt = (hours - 8.0).coerceAtLeast(0.0)
        return Triple(0.0, ot, dt)
    }

    private fun findSeventhDayDates(dates: Set<LocalDate>, weekStartsOn: DayOfWeek): Set<LocalDate> {
        if (dates.isEmpty()) return emptySet()

        // Group dates by workweek start.
        val byWeekStart = dates.groupBy { weekStartFor(it, weekStartsOn) }

        val out = mutableSetOf<LocalDate>()
        for ((weekStart, ds) in byWeekStart) {
            // We consider it the 7th consecutive day if there is a time entry for
            // each of the 7 days in the workweek.
            val present = ds.toSet()
            val all7 = (0..6).all { i -> weekStart.plusDays(i.toLong()) in present }
            if (all7) {
                out.add(weekStart.plusDays(6))
            }
        }
        return out
    }

    private fun weekStartFor(date: LocalDate, weekStartsOn: DayOfWeek): LocalDate {
        var d = date
        while (d.dayOfWeek != weekStartsOn) {
            d = d.minusDays(1)
        }
        return d
    }
}
