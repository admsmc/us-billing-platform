package com.example.uspayroll.labor.impl

import com.example.uspayroll.labor.api.LaborStandardsCatalog
import com.example.uspayroll.labor.api.LaborStandardsContextProvider
import com.example.uspayroll.labor.api.LaborStandardsQuery
import com.example.uspayroll.labor.api.StateLaborStandard
import com.example.uspayroll.payroll.model.LaborStandardsContext
import com.example.uspayroll.shared.EmployerId
import com.example.uspayroll.shared.Money
import java.time.LocalDate

class InMemoryLaborStandardsCatalog : LaborStandardsCatalog {
    private val standards: List<StateLaborStandard> = listOf(
        StateLaborStandard(
            stateCode = "CA",
            effectiveFrom = LocalDate.of(2025, 1, 1),
            effectiveTo = null,
            regularMinimumWageCents = 16_50L,
            tippedMinimumCashWageCents = 16_50L,
            maxTipCreditCents = 0L,
            weeklyOvertimeThresholdHours = 40.0,
            dailyOvertimeThresholdHours = 8.0,
            dailyDoubleTimeThresholdHours = 12.0,
        ),
        StateLaborStandard(
            stateCode = "TX",
            effectiveFrom = LocalDate.of(2025, 1, 1),
            effectiveTo = null,
            regularMinimumWageCents = 7_25L,
            tippedMinimumCashWageCents = 2_13L,
            maxTipCreditCents = 5_12L,
            weeklyOvertimeThresholdHours = 40.0,
        ),
    )

    override fun loadStateStandard(query: LaborStandardsQuery): StateLaborStandard? {
        val state = query.workState ?: return null
        val asOf = query.asOfDate
        // In-memory implementation currently ignores localityCodes and only
        // matches on state + effective date.
        return standards.firstOrNull { s ->
            s.stateCode.equals(state, ignoreCase = true) &&
                !asOf.isBefore(s.effectiveFrom) &&
                (s.effectiveTo == null || !asOf.isAfter(s.effectiveTo))
        }
    }

    override fun listStateStandards(asOfDate: LocalDate?): List<StateLaborStandard> = if (asOfDate == null) {
        standards
    } else {
        standards.filter { s ->
            !asOfDate.isBefore(s.effectiveFrom) &&
                (s.effectiveTo == null || !asOfDate.isAfter(s.effectiveTo))
        }
    }
}

class CatalogBackedLaborStandardsContextProvider(
    private val catalog: LaborStandardsCatalog,
) : LaborStandardsContextProvider {

    override fun getLaborStandards(employerId: EmployerId, asOfDate: LocalDate, workState: String?, homeState: String?, localityCodes: List<String>): LaborStandardsContext? {
        if (workState == null) return null

        val query = LaborStandardsQuery(
            employerId = employerId,
            asOfDate = asOfDate,
            workState = workState,
            homeState = homeState,
            localityCodes = localityCodes,
        )
        val state = catalog.loadStateStandard(query) ?: return null

        return LaborStandardsContext(
            federalMinimumWage = state.regularMinimumWageCents?.let { Money(it) } ?: Money(7_25L),
            youthMinimumWage = null,
            youthMaxAgeYears = null,
            youthMaxConsecutiveDaysFromHire = null,
            federalTippedCashMinimum = state.tippedMinimumCashWageCents?.let { Money(it) },
            tippedMonthlyThreshold = null,
        )
    }
}
