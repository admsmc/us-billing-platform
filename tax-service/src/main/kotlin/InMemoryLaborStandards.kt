// moved to labor-service

import com.example.uspayroll.labor.api.*
import com.example.uspayroll.payroll.model.LaborStandardsContext
import com.example.uspayroll.shared.Money
import java.time.LocalDate

/**
 * Simple in-memory LaborStandardsCatalog with a couple of example states
 * (CA, TX). This is intended for development and testing; a real
 * implementation would load from a database or configuration.
 */
class InMemoryLaborStandardsCatalog : LaborStandardsCatalog {

    private val standards: List<StateLaborStandard> = listOf(
        // California example: no tip credit, daily OT after 8, double-time after 12.
        StateLaborStandard(
            stateCode = "CA",
            effectiveFrom = LocalDate.of(2025, 1, 1),
            effectiveTo = null,
            regularMinimumWageCents = 16_00L, // $16.00/hr (example value)
            tippedMinimumCashWageCents = 16_00L,
            maxTipCreditCents = 0L,
            weeklyOvertimeThresholdHours = 40.0,
            dailyOvertimeThresholdHours = 8.0,
            dailyDoubleTimeThresholdHours = 12.0,
            sources = listOf(
                LaborStandardSourceRef(
                    kind = LaborStandardSourceKind.FEDERAL_DOL_MIN_WAGE_TABLE,
                    citation = "State minimum wage table (example)",
                ),
            ),
        ),
        // Texas example: follows federal minimum wage and tip credit, no daily OT.
        StateLaborStandard(
            stateCode = "TX",
            effectiveFrom = LocalDate.of(2025, 1, 1),
            effectiveTo = null,
            regularMinimumWageCents = 7_25L,  // $7.25/hr
            tippedMinimumCashWageCents = 2_13L, // $2.13/hr
            maxTipCreditCents = 5_12L, // 7.25 - 2.13 = 5.12
            weeklyOvertimeThresholdHours = 40.0,
            dailyOvertimeThresholdHours = null,
            dailyDoubleTimeThresholdHours = null,
            sources = listOf(
                LaborStandardSourceRef(
                    kind = LaborStandardSourceKind.FEDERAL_DOL_MIN_WAGE_TABLE,
                    citation = "State minimum wage table (example)",
                ),
                LaborStandardSourceRef(
                    kind = LaborStandardSourceKind.FEDERAL_DOL_TIPPED_WAGE_TABLE,
                    citation = "State tipped minimum wage table (example)",
                ),
            ),
        ),
    )

    override fun loadStateStandard(query: LaborStandardsQuery): StateLaborStandard? {
        val state = query.workState ?: return null
        val asOf = query.asOfDate
        return standards.firstOrNull { s ->
            s.stateCode.equals(state, ignoreCase = true) &&
                !asOf.isBefore(s.effectiveFrom) &&
                (s.effectiveTo == null || !asOf.isAfter(s.effectiveTo))
        }
    }

    override fun listStateStandards(asOfDate: LocalDate?): List<StateLaborStandard> {
        return if (asOfDate == null) standards else standards.filter { s ->
            !asOfDate.isBefore(s.effectiveFrom) &&
                (s.effectiveTo == null || !asOfDate.isAfter(s.effectiveTo))
        }
    }
}

/**
 * Port interface exposed to the worker/orchestrator for obtaining a
 * LaborStandardsContext suitable for the payroll engine.
 */
interface LaborStandardsContextProvider {
    fun getLaborStandards(
        employerId: com.example.uspayroll.shared.EmployerId,
        asOfDate: LocalDate,
        workState: String?,
        homeState: String?,
    ): LaborStandardsContext?
}

/**
 * Adapter that maps a StateLaborStandard record from the catalog into the
 * engine-facing LaborStandardsContext.
 */
class CatalogBackedLaborStandardsContextProvider(
    private val catalog: LaborStandardsCatalog,
) : LaborStandardsContextProvider {

    override fun getLaborStandards(
        employerId: com.example.uspayroll.shared.EmployerId,
        asOfDate: LocalDate,
        workState: String?,
        homeState: String?,
    ): LaborStandardsContext? {
        if (workState == null) return null
        val query = LaborStandardsQuery(
            employerId = employerId,
            asOfDate = asOfDate,
            workState = workState,
            homeState = homeState,
        )
        val stateStandard = catalog.loadStateStandard(query) ?: return null
        return mapToContext(stateStandard)
    }

    private fun mapToContext(state: StateLaborStandard): LaborStandardsContext {
        // Convert cents-based fields into Money for the engine context.
        val regular = state.regularMinimumWageCents?.let { Money(it) }
        val tippedCash = state.tippedMinimumCashWageCents?.let { Money(it) }

        return LaborStandardsContext(
            federalMinimumWage = regular ?: Money(7_25L), // fallback to federal floor
            youthMinimumWage = null, // not modeled per-state yet
            youthMaxAgeYears = null,
            youthMaxConsecutiveDaysFromHire = null,
            federalTippedCashMinimum = tippedCash,
            tippedMonthlyThreshold = null, // could be filled from DOL or config later
        )
    }
}
