package com.example.uspayroll.labor.tools

import com.example.uspayroll.labor.api.StateLaborStandard
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.time.LocalDate

internal object LaborStandardsArtifactRenderer {

    private val objectMapper = jacksonObjectMapper()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    fun renderJson(standards: List<StateLaborStandard>): String = objectMapper
        .writerWithDefaultPrettyPrinter()
        .writeValueAsString(standards) + "\n"

    fun renderSql(year: String, standards: List<StateLaborStandard>): String {
        val sb = StringBuilder()
        sb.appendLine("-- Generated labor_standard rows; laborYear=$year")

        standards.forEach { standard ->
            sb.appendLine(buildInsert(standard))
            sb.appendLine()
        }

        return sb.toString()
    }

    private fun buildInsert(s: StateLaborStandard): String {
        fun sqlDate(d: LocalDate?): String = d?.let { "DATE '$it'" } ?: "NULL"
        fun sqlLong(v: Long?): String = v?.toString() ?: "NULL"
        fun sqlDouble(v: Double?): String = v?.toString() ?: "NULL"
        fun sqlString(v: String?): String = v?.let { "'$it'" } ?: "NULL"

        return """
            |INSERT INTO labor_standard (
            |    state_code,
            |    locality_code,
            |    locality_kind,
            |    effective_from,
            |    effective_to,
            |    regular_minimum_wage_cents,
            |    tipped_minimum_cash_wage_cents,
            |    max_tip_credit_cents,
            |    weekly_ot_threshold_hours,
            |    daily_ot_threshold_hours,
            |    daily_dt_threshold_hours
            |) VALUES (
            |    '${s.stateCode}',
            |    ${sqlString(s.localityCode)},
            |    ${sqlString(s.localityKind)},
            |    ${sqlDate(s.effectiveFrom)},
            |    ${sqlDate(s.effectiveTo)},
            |    ${sqlLong(s.regularMinimumWageCents)},
            |    ${sqlLong(s.tippedMinimumCashWageCents)},
            |    ${sqlLong(s.maxTipCreditCents)},
            |    ${sqlDouble(s.weeklyOvertimeThresholdHours)},
            |    ${sqlDouble(s.dailyOvertimeThresholdHours)},
            |    ${sqlDouble(s.dailyDoubleTimeThresholdHours)}
            |);
        """.trimMargin()
    }
}
