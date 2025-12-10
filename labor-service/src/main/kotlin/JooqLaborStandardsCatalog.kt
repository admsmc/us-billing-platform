package com.example.uspayroll.labor.persistence

import com.example.uspayroll.labor.api.LaborStandardsCatalog
import com.example.uspayroll.labor.api.LaborStandardsQuery
import com.example.uspayroll.labor.api.StateLaborStandard
import org.jooq.Condition
import org.jooq.DSLContext
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
            stateCode = record.get("state_code", String::class.java)!!,
            effectiveFrom = record.get("effective_from", LocalDate::class.java)!!,
            effectiveTo = record.get("effective_to", LocalDate::class.java),
            regularMinimumWageCents = record.get("regular_minimum_wage_cents", Long::class.java),
            tippedMinimumCashWageCents = record.get("tipped_minimum_cash_wage_cents", Long::class.java),
            maxTipCreditCents = record.get("max_tip_credit_cents", Long::class.java),
            weeklyOvertimeThresholdHours = record.get("weekly_ot_threshold_hours", Double::class.java) ?: 40.0,
            dailyOvertimeThresholdHours = record.get("daily_ot_threshold_hours", Double::class.java),
            dailyDoubleTimeThresholdHours = record.get("daily_dt_threshold_hours", Double::class.java),
            localityCode = record.get("locality_code", String::class.java),
            localityKind = record.get("locality_kind", String::class.java),
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
                stateCode = r.get("state_code", String::class.java)!!,
                effectiveFrom = r.get("effective_from", LocalDate::class.java)!!,
                effectiveTo = r.get("effective_to", LocalDate::class.java),
                regularMinimumWageCents = r.get("regular_minimum_wage_cents", Long::class.java),
                tippedMinimumCashWageCents = r.get("tipped_minimum_cash_wage_cents", Long::class.java),
                maxTipCreditCents = r.get("max_tip_credit_cents", Long::class.java),
                weeklyOvertimeThresholdHours = r.get("weekly_ot_threshold_hours", Double::class.java) ?: 40.0,
                dailyOvertimeThresholdHours = r.get("daily_ot_threshold_hours", Double::class.java),
                dailyDoubleTimeThresholdHours = r.get("daily_dt_threshold_hours", Double::class.java),
                localityCode = r.get("locality_code", String::class.java),
                localityKind = r.get("locality_kind", String::class.java),
            )
        }
    }
}
