package com.example.usbilling.hr.payperiod

import com.example.usbilling.payroll.model.PayFrequency
import java.time.LocalDate
import java.time.Year

object PayPeriodGeneration {

    data class PaySchedule(
        val employerId: String,
        val scheduleId: String,
        val frequency: PayFrequency,
        val firstStartDate: LocalDate,
        val checkDateOffsetDays: Int,
        val semiMonthlyFirstEndDay: Int? = null,
    )

    data class GeneratedPayPeriod(
        val employerId: String,
        val payPeriodId: String,
        val startDate: LocalDate,
        val endDate: LocalDate,
        val checkDate: LocalDate,
        val frequency: PayFrequency,
        val sequenceInYear: Int,
    )

    fun generateForYear(schedule: PaySchedule, year: Int): List<GeneratedPayPeriod> {
        require(year in 1900..9999) { "year out of range" }
        require(schedule.checkDateOffsetDays >= 0) { "checkDateOffsetDays must be >= 0" }

        // Sequence is defined within a calendar year by check date.
        // This aligns with payroll/tax concepts where a pay period's "year" is driven by its check date.
        val startOfYear = Year.of(year).atDay(1)
        val endOfYear = Year.of(year).atMonth(12).atEndOfMonth()

        return when (schedule.frequency) {
            PayFrequency.WEEKLY -> generateFixedDays(schedule, year, startOfYear, endOfYear, stepDays = 7)
            PayFrequency.BIWEEKLY -> generateFixedDays(schedule, year, startOfYear, endOfYear, stepDays = 14)
            PayFrequency.FOUR_WEEKLY -> generateFixedDays(schedule, year, startOfYear, endOfYear, stepDays = 28)
            PayFrequency.MONTHLY -> generateByMonths(schedule, year, startOfYear, endOfYear, stepMonths = 1)
            PayFrequency.QUARTERLY -> generateByMonths(schedule, year, startOfYear, endOfYear, stepMonths = 3)
            PayFrequency.ANNUAL -> generateByYears(schedule, year, startOfYear, endOfYear)
            PayFrequency.SEMI_MONTHLY -> generateSemiMonthly(schedule, year, startOfYear, endOfYear)
        }
    }

    private fun generateFixedDays(schedule: PaySchedule, year: Int, startOfYear: LocalDate, endOfYear: LocalDate, stepDays: Long): List<GeneratedPayPeriod> {
        var start = schedule.firstStartDate

        // Advance until the computed checkDate lands within (or after) the target year.
        while (true) {
            val end = start.plusDays(stepDays - 1)
            val checkDate = end.plusDays(schedule.checkDateOffsetDays.toLong())
            if (!checkDate.isBefore(startOfYear)) break
            start = start.plusDays(stepDays)
        }

        val out = mutableListOf<GeneratedPayPeriod>()
        var seq = 1
        while (true) {
            val end = start.plusDays(stepDays - 1)
            val checkDate = end.plusDays(schedule.checkDateOffsetDays.toLong())
            if (checkDate.isAfter(endOfYear)) break

            out += GeneratedPayPeriod(
                employerId = schedule.employerId,
                payPeriodId = payPeriodId(schedule.scheduleId, year, seq),
                startDate = start,
                endDate = end,
                checkDate = checkDate,
                frequency = schedule.frequency,
                sequenceInYear = seq,
            )

            start = start.plusDays(stepDays)
            seq += 1
        }
        return out
    }

    private fun generateByMonths(schedule: PaySchedule, year: Int, startOfYear: LocalDate, endOfYear: LocalDate, stepMonths: Long): List<GeneratedPayPeriod> {
        var start = schedule.firstStartDate

        while (true) {
            val end = start.plusMonths(stepMonths).minusDays(1)
            val checkDate = end.plusDays(schedule.checkDateOffsetDays.toLong())
            if (!checkDate.isBefore(startOfYear)) break
            start = start.plusMonths(stepMonths)
        }

        val out = mutableListOf<GeneratedPayPeriod>()
        var seq = 1
        while (true) {
            val end = start.plusMonths(stepMonths).minusDays(1)
            val checkDate = end.plusDays(schedule.checkDateOffsetDays.toLong())
            if (checkDate.isAfter(endOfYear)) break

            out += GeneratedPayPeriod(
                employerId = schedule.employerId,
                payPeriodId = payPeriodId(schedule.scheduleId, year, seq),
                startDate = start,
                endDate = end,
                checkDate = checkDate,
                frequency = schedule.frequency,
                sequenceInYear = seq,
            )
            start = start.plusMonths(stepMonths)
            seq += 1
        }
        return out
    }

    private fun generateByYears(schedule: PaySchedule, year: Int, startOfYear: LocalDate, endOfYear: LocalDate): List<GeneratedPayPeriod> {
        var start = schedule.firstStartDate

        while (true) {
            val end = start.plusYears(1).minusDays(1)
            val checkDate = end.plusDays(schedule.checkDateOffsetDays.toLong())
            if (!checkDate.isBefore(startOfYear)) break
            start = start.plusYears(1)
        }

        val out = mutableListOf<GeneratedPayPeriod>()
        var seq = 1
        while (true) {
            val end = start.plusYears(1).minusDays(1)
            val checkDate = end.plusDays(schedule.checkDateOffsetDays.toLong())
            if (checkDate.isAfter(endOfYear)) break

            out += GeneratedPayPeriod(
                employerId = schedule.employerId,
                payPeriodId = payPeriodId(schedule.scheduleId, year, seq),
                startDate = start,
                endDate = end,
                checkDate = checkDate,
                frequency = schedule.frequency,
                sequenceInYear = seq,
            )
            start = start.plusYears(1)
            seq += 1
        }
        return out
    }

    private fun generateSemiMonthly(schedule: PaySchedule, year: Int, startOfYear: LocalDate, endOfYear: LocalDate): List<GeneratedPayPeriod> {
        val firstEndDay = requireNotNull(schedule.semiMonthlyFirstEndDay) {
            "semiMonthlyFirstEndDay is required for SEMI_MONTHLY schedules"
        }
        require(firstEndDay in 1..27) { "semiMonthlyFirstEndDay must be in 1..27" }
        // For now, keep the model deterministic and simple: anchor must start on the first of a month.
        require(schedule.firstStartDate.dayOfMonth == 1) { "SEMI_MONTHLY schedules require firstStartDate on the 1st" }

        var monthCursor = LocalDate.of(schedule.firstStartDate.year, schedule.firstStartDate.month, 1)
        while (monthCursor.isBefore(LocalDate.of(startOfYear.year, 1, 1))) {
            monthCursor = monthCursor.plusMonths(1)
        }

        // Advance monthCursor until the first computed check date lands within the target year.
        while (true) {
            val monthEnd = monthCursor.withDayOfMonth(monthCursor.lengthOfMonth())
            val p1End = monthCursor.withDayOfMonth(firstEndDay)
            val p2End = monthEnd
            val candidateCheckDates = listOf(p1End, p2End).map { it.plusDays(schedule.checkDateOffsetDays.toLong()) }
            if (candidateCheckDates.any { !it.isBefore(startOfYear) }) break
            monthCursor = monthCursor.plusMonths(1)
        }

        val out = mutableListOf<GeneratedPayPeriod>()
        var seq = 1
        while (!monthCursor.isAfter(endOfYear)) {
            val monthEnd = monthCursor.withDayOfMonth(monthCursor.lengthOfMonth())

            val p1Start = monthCursor
            val p1End = monthCursor.withDayOfMonth(firstEndDay)
            val p2Start = p1End.plusDays(1)
            val p2End = monthEnd

            listOf(p1Start to p1End, p2Start to p2End)
                .forEach { (s, e) ->
                    val checkDate = e.plusDays(schedule.checkDateOffsetDays.toLong())
                    if (checkDate.isBefore(startOfYear) || checkDate.isAfter(endOfYear)) return@forEach

                    out += GeneratedPayPeriod(
                        employerId = schedule.employerId,
                        payPeriodId = payPeriodId(schedule.scheduleId, year, seq),
                        startDate = s,
                        endDate = e,
                        checkDate = checkDate,
                        frequency = schedule.frequency,
                        sequenceInYear = seq,
                    )
                    seq += 1
                }

            monthCursor = monthCursor.plusMonths(1)
        }

        return out
    }

    private fun payPeriodId(scheduleId: String, year: Int, seq: Int): String {
        val seqPart = seq.toString().padStart(2, '0')
        return "$year-$scheduleId-$seqPart"
    }
}
