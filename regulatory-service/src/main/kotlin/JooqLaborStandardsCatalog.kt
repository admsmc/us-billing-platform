package com.example.usbilling.labor.persistence

import com.example.usbilling.labor.api.LaborStandardsCatalog
import com.example.usbilling.labor.api.LaborStandardsQuery
import com.example.usbilling.labor.api.StateLaborStandard
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

    // Prefer typed fields over runtime-discovered fields. This reduces reliance on JDBC/driver metadata
    // and avoids repeated pg_catalog lookups under high request volume.
    // Use string-based identifiers so H2 (unquoted identifiers -> uppercased) stays compatible
    // while still keeping field types explicit.
    private val t = DSL.table("labor_standard")

    private val fStateCode = DSL.field("state_code", String::class.java)
    private val fEffectiveFrom = DSL.field("effective_from", LocalDate::class.java)
    private val fEffectiveTo = DSL.field("effective_to", LocalDate::class.java)
    private val fRegularMinWageCents = DSL.field("regular_minimum_wage_cents", java.lang.Long::class.java)
    private val fTippedMinCashWageCents = DSL.field("tipped_minimum_cash_wage_cents", java.lang.Long::class.java)
    private val fMaxTipCreditCents = DSL.field("max_tip_credit_cents", java.lang.Long::class.java)
    private val fWeeklyOtThresholdHours = DSL.field("weekly_ot_threshold_hours", java.lang.Double::class.java)
    private val fDailyOtThresholdHours = DSL.field("daily_ot_threshold_hours", java.lang.Double::class.java)
    private val fDailyDtThresholdHours = DSL.field("daily_dt_threshold_hours", java.lang.Double::class.java)
    private val fLocalityCode = DSL.field("locality_code", String::class.java)
    private val fLocalityKind = DSL.field("locality_kind", String::class.java)

    override fun loadStateStandard(query: LaborStandardsQuery): StateLaborStandard? {
        val state = query.workState ?: return null
        val asOf = query.asOfDate
        val conditions = mutableListOf<Condition>()
        conditions += fStateCode.eq(state)
        conditions += fEffectiveFrom.le(asOf)
        conditions += DSL.or(
            fEffectiveTo.isNull,
            fEffectiveTo.ge(asOf),
        )

        // If locality codes are provided, restrict to rows that are either
        // generic (NULL locality_code) or whose locality_code matches one of
        // the requested codes. When none are provided, restrict to statewide
        // rows only so that generic baselines are used.
        val localityCodes = query.localityCodes.map { it.uppercase() }
        if (localityCodes.isEmpty()) {
            conditions += fLocalityCode.isNull
        } else {
            conditions += DSL.or(
                fLocalityCode.isNull,
                fLocalityCode.`in`(localityCodes),
            )
        }

        val whereCondition = conditions
            .fold<Condition, Condition?>(null) { acc, c -> acc?.and(c) ?: c }
            ?: DSL.noCondition()

        val orderBy = if (localityCodes.isNotEmpty()) {
            listOf(
                // Local rows (non-null locality_code) first, then statewide.
                fLocalityCode.isNotNull.desc(),
                fEffectiveFrom.desc(),
            )
        } else {
            listOf(fEffectiveFrom.desc())
        }

        val record = dsl
            .select(
                fStateCode,
                fEffectiveFrom,
                fEffectiveTo,
                fRegularMinWageCents,
                fTippedMinCashWageCents,
                fMaxTipCreditCents,
                fWeeklyOtThresholdHours,
                fDailyOtThresholdHours,
                fDailyDtThresholdHours,
                fLocalityCode,
                fLocalityKind,
            )
            .from(t)
            .where(whereCondition)
            .orderBy(orderBy)
            .limit(1)
            .fetchOne() ?: return null

        return StateLaborStandard(
            stateCode = requireNotNull(record.get(fStateCode)),
            effectiveFrom = requireNotNull(record.get(fEffectiveFrom)),
            effectiveTo = record.get(fEffectiveTo),
            regularMinimumWageCents = record.get(fRegularMinWageCents)?.toLong(),
            tippedMinimumCashWageCents = record.get(fTippedMinCashWageCents)?.toLong(),
            maxTipCreditCents = record.get(fMaxTipCreditCents)?.toLong(),
            weeklyOvertimeThresholdHours = record.get(fWeeklyOtThresholdHours)?.toDouble() ?: 40.0,
            dailyOvertimeThresholdHours = record.get(fDailyOtThresholdHours)?.toDouble(),
            dailyDoubleTimeThresholdHours = record.get(fDailyDtThresholdHours)?.toDouble(),
            localityCode = record.get(fLocalityCode),
            localityKind = record.get(fLocalityKind),
        )
    }

    override fun listStateStandards(asOfDate: LocalDate?): List<StateLaborStandard> {
        var whereCondition: Condition = DSL.noCondition()
        if (asOfDate != null) {
            whereCondition = whereCondition
                .and(fEffectiveFrom.le(asOfDate))
                .and(
                    DSL.or(
                        fEffectiveTo.isNull,
                        fEffectiveTo.ge(asOfDate),
                    ),
                )
        }

        val records = dsl
            .select(
                fStateCode,
                fEffectiveFrom,
                fEffectiveTo,
                fRegularMinWageCents,
                fTippedMinCashWageCents,
                fMaxTipCreditCents,
                fWeeklyOtThresholdHours,
                fDailyOtThresholdHours,
                fDailyDtThresholdHours,
                fLocalityCode,
                fLocalityKind,
            )
            .from(t)
            .where(whereCondition)
            .fetch()

        return records.map { r ->
            StateLaborStandard(
                stateCode = requireNotNull(r.get(fStateCode)),
                effectiveFrom = requireNotNull(r.get(fEffectiveFrom)),
                effectiveTo = r.get(fEffectiveTo),
                regularMinimumWageCents = r.get(fRegularMinWageCents)?.toLong(),
                tippedMinimumCashWageCents = r.get(fTippedMinCashWageCents)?.toLong(),
                maxTipCreditCents = r.get(fMaxTipCreditCents)?.toLong(),
                weeklyOvertimeThresholdHours = r.get(fWeeklyOtThresholdHours)?.toDouble() ?: 40.0,
                dailyOvertimeThresholdHours = r.get(fDailyOtThresholdHours)?.toDouble(),
                dailyDoubleTimeThresholdHours = r.get(fDailyDtThresholdHours)?.toDouble(),
                localityCode = r.get(fLocalityCode),
                localityKind = r.get(fLocalityKind),
            )
        }
    }
}
