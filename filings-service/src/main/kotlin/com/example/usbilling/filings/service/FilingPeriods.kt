package com.example.usbilling.filings.service

import java.time.LocalDate

object FilingPeriods {

    data class DateRange(val startInclusive: LocalDate, val endInclusive: LocalDate)

    fun quarter(year: Int, quarter: Int): DateRange {
        require(quarter in 1..4) { "quarter must be in 1..4" }

        return when (quarter) {
            1 -> DateRange(LocalDate.of(year, 1, 1), LocalDate.of(year, 3, 31))
            2 -> DateRange(LocalDate.of(year, 4, 1), LocalDate.of(year, 6, 30))
            3 -> DateRange(LocalDate.of(year, 7, 1), LocalDate.of(year, 9, 30))
            4 -> DateRange(LocalDate.of(year, 10, 1), LocalDate.of(year, 12, 31))
            else -> error("unreachable")
        }
    }

    fun year(year: Int): DateRange = DateRange(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31))
}
