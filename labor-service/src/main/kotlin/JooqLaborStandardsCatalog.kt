package com.example.uspayroll.labor.persistence

import com.example.uspayroll.labor.api.LaborStandardsCatalog
import com.example.uspayroll.labor.api.LaborStandardsQuery
import com.example.uspayroll.labor.api.StateLaborStandard
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import java.time.LocalDate

/**
 * jOOQ-based implementation of [LaborStandardsCatalog] backed by a Postgres
 * `labor_standard` table. This mirrors the shape of JooqTaxRuleRepository but
 * is intentionally minimal and focused on per-state, per-date lookup.
 */
class JooqLaborStandardsCatalog(
    private val dsl: DSLContext,
) : LaborStandardsCatalog {

    private fun <T> Record.getCaseInsensitive(fieldName: String, type: Class<T>): T? {
        val candidates = listOf(fieldName, fieldName.lowercase(), fieldName.uppercase())
        for (candidate in candidates) {
            val value = runCatching { get(candidate, type) }.getOrNull()
            if (value != null) return value
        }
        // If the field exists but the value is NULL, the above will keep trying. Handle that case.
        for (candidate in candidates) {
            val exists = runCatching { fields().any { it.name == candidate } }.getOrDefault(false)
            if (exists) return runCatching { get(candidate, type) }.getOrNull()
        }
        return null
    }

    override fun loadStateStandard(query: LaborStandardsQuery): StateLaborStandard? {
        val state = query.workState ?: return null
        val asOf = query.asOfDate
        val t = DSL.table("labor_standard")

        val conditions = mutableListOf<Condition>()
        conditions += DSL.field("state_code").eq(state)
        conditions += DSL.field("effective_from", LocalDate::class.java).le(asOf)
        conditions += DSL.or(
            DSL.field("effective_to", LocalDate::class.java).isNull,
            DSL.field("effective_to", LocalDate::class.java).ge(asOf),
        )

        // If locality codes are provided, restrict to rows that are either
        // generic (NULL locality_code) or whose locality_code matches one of
        // the requested codes. When none are provided, restrict to statewide
        // rows only so that generic baselines are used.
        val localityCodes = query.localityCodes.map { it.uppercase() }
        if (localityCodes.isEmpty()) {
            conditions += DSL.field("locality_code").isNull
        } else {
            conditions += DSL.or(
                DSL.field("locality_code").isNull,
                DSL.field("locality_code").`in`(localityCodes),
            )
        }

        val whereCondition = conditions
            .fold<Condition, Condition?>(null) { acc, c -> acc?.and(c) ?: c }
            ?: DSL.noCondition()

        val orderBy = if (localityCodes.isNotEmpty()) {
            listOf(
                // Local rows (non-null locality_code) first, then statewide.
                DSL.field("locality_code").isNotNull.desc(),
                DSL.field("effective_from").desc(),
            )
        } else {
            listOf(DSL.field("effective_from").desc())
        }

        val record = dsl
            .selectFrom(t)
            .where(whereCondition)
            .orderBy(orderBy)
            .limit(1)
            .fetchOne() ?: return null

        return StateLaborStandard(
            stateCode = requireNotNull(record.getCaseInsensitive("state_code", String::class.java)),
            effectiveFrom = requireNotNull(record.getCaseInsensitive("effective_from", LocalDate::class.java)),
            effectiveTo = record.getCaseInsensitive("effective_to", LocalDate::class.java),
            regularMinimumWageCents = record.getCaseInsensitive("regular_minimum_wage_cents", java.lang.Long::class.java)?.toLong(),
            tippedMinimumCashWageCents = record.getCaseInsensitive("tipped_minimum_cash_wage_cents", java.lang.Long::class.java)?.toLong(),
            maxTipCreditCents = record.getCaseInsensitive("max_tip_credit_cents", java.lang.Long::class.java)?.toLong(),
            weeklyOvertimeThresholdHours = record.getCaseInsensitive("weekly_ot_threshold_hours", java.lang.Double::class.java)?.toDouble() ?: 40.0,
            dailyOvertimeThresholdHours = record.getCaseInsensitive("daily_ot_threshold_hours", java.lang.Double::class.java)?.toDouble(),
            dailyDoubleTimeThresholdHours = record.getCaseInsensitive("daily_dt_threshold_hours", java.lang.Double::class.java)?.toDouble(),
            localityCode = record.getCaseInsensitive("locality_code", String::class.java),
            localityKind = record.getCaseInsensitive("locality_kind", String::class.java),
        )
    }

    override fun listStateStandards(asOfDate: LocalDate?): List<StateLaborStandard> {
        val t = DSL.table("labor_standard")
        var whereCondition: Condition = DSL.noCondition()
        if (asOfDate != null) {
            whereCondition = whereCondition
                .and(DSL.field("effective_from", LocalDate::class.java).le(asOfDate))
                .and(
                    DSL.or(
                        DSL.field("effective_to", LocalDate::class.java).isNull,
                        DSL.field("effective_to", LocalDate::class.java).ge(asOfDate),
                    ),
                )
        }

        val records = dsl
            .selectFrom(t)
            .where(whereCondition)
            .fetch()

        return records.map { r ->
            StateLaborStandard(
                stateCode = requireNotNull(r.getCaseInsensitive("state_code", String::class.java)),
                effectiveFrom = requireNotNull(r.getCaseInsensitive("effective_from", LocalDate::class.java)),
                effectiveTo = r.getCaseInsensitive("effective_to", LocalDate::class.java),
                regularMinimumWageCents = r.getCaseInsensitive("regular_minimum_wage_cents", java.lang.Long::class.java)?.toLong(),
                tippedMinimumCashWageCents = r.getCaseInsensitive("tipped_minimum_cash_wage_cents", java.lang.Long::class.java)?.toLong(),
                maxTipCreditCents = r.getCaseInsensitive("max_tip_credit_cents", java.lang.Long::class.java)?.toLong(),
                weeklyOvertimeThresholdHours = r.getCaseInsensitive("weekly_ot_threshold_hours", java.lang.Double::class.java)?.toDouble() ?: 40.0,
                dailyOvertimeThresholdHours = r.getCaseInsensitive("daily_ot_threshold_hours", java.lang.Double::class.java)?.toDouble(),
                dailyDoubleTimeThresholdHours = r.getCaseInsensitive("daily_dt_threshold_hours", java.lang.Double::class.java)?.toDouble(),
                localityCode = r.getCaseInsensitive("locality_code", String::class.java),
                localityKind = r.getCaseInsensitive("locality_kind", String::class.java),
            )
        }
    }
}
