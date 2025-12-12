// moved to labor-service

import com.example.uspayroll.shared.EmployerId
import java.time.LocalDate

/**
 * Query for loading labor standards (minimum wage, tip credit, overtime) from
 * the catalog. This is intentionally simple and focused on state-level rules
 * so it can be populated directly from DOL 50-state tables and state sources.
 */
data class LaborStandardsQuery(
    val employerId: EmployerId,
    val asOfDate: LocalDate,
    /**
     * Primary work state code (e.g. "CA", "NY"). This is typically the key
     * used to select the applicable state labor standards.
     */
    val workState: String? = null,
    /**
     * Home state may be relevant for some edge cases; included for symmetry
     * with the tax query, but is not required for basic minimum wage logic.
     */
    val homeState: String? = null,
)

/**
 * Enumerates the kinds of external sources used to populate a
 * [StateLaborStandard]. This lets the catalog retain provenance back to
 * federal DOL tables and state statutes.
 */
enum class LaborStandardSourceKind {
    /** U.S. DOL "State Minimum Wage Laws" table. */
    FEDERAL_DOL_MIN_WAGE_TABLE,

    /** U.S. DOL "Minimum Wages for Tipped Employees" table. */
    FEDERAL_DOL_TIPPED_WAGE_TABLE,

    /** State statute (e.g., state labor code section). */
    STATE_STATUTE,

    /** State administrative rule or regulation. */
    STATE_ADMIN_RULE,

    /** Other vetted source (agency guidance, official FAQ, etc.). */
    OTHER,
}

/**
 * Reference to a specific external source for a labor standard entry. This is
 * not meant to be exhaustive legal metadata, just enough for traceability.
 */
data class LaborStandardSourceRef(
    val kind: LaborStandardSourceKind,
    /**
     * Free-form description or citation, e.g. "29 CFR ..." or
     * "CA Labor Code §1182.12".
     */
    val citation: String? = null,
    /** Optional URL to the DOL table or state code page. */
    val url: String? = null,
)

/**
 * State-level labor standard record, per state and effective date range.
 *
 * This is the "raw" catalog shape that can be populated directly from DOL
 * 50-state tables and state law. A higher-level adapter can later map one or
 * more [StateLaborStandard] records into the engine-facing
 * `LaborStandardsContext` for a given employee and pay period.
 */
data class StateLaborStandard(
    /** Two-letter state or territory code, e.g. "CA", "NY". */
    val stateCode: String,
    /** Inclusive effective start date for this record. */
    val effectiveFrom: LocalDate,
    /** Optional inclusive end date; null means open-ended. */
    val effectiveTo: LocalDate? = null,

    // --- Minimum wage ---

    /** Regular minimum wage per hour in cents (e.g. 725 for $7.25). */
    val regularMinimumWageCents: Long?,
    /**
     * Tipped employee minimum cash wage per hour in cents, if the state
     * permits a tip credit and defines a lower cash wage for tipped roles.
     */
    val tippedMinimumCashWageCents: Long? = null,
    /**
     * Maximum tip credit per hour in cents (difference between regular
     * minimum and tipped cash minimum), where the state allows it.
     */
    val maxTipCreditCents: Long? = null,

    // --- Overtime / premium pay semantics ---

    /**
     * Weekly overtime threshold in hours. For most states this is 40 and
     * simply mirrors FLSA; states that diverge can override.
     */
    val weeklyOvertimeThresholdHours: Double = 40.0,
    /**
     * Daily overtime threshold in hours, where state law requires daily OT
     * (e.g., 8.0 in California). Null means "no separate daily OT rule".
     */
    val dailyOvertimeThresholdHours: Double? = null,
    /**
     * Daily double-time threshold in hours, for states that require double
     * time beyond a certain daily limit (e.g., 12.0 in California).
     */
    val dailyDoubleTimeThresholdHours: Double? = null,

    // --- Provenance ---

    /** External source references (DOL tables, statutes, etc.). */
    val sources: List<LaborStandardSourceRef> = emptyList(),
)

/**
 * Catalog of labor standards backed by a data store (e.g., database or
 * configuration files). This boundary does not know about payroll; it simply
 * exposes state-level labor standard records that are valid for a given
 * [LaborStandardsQuery].
 */
interface LaborStandardsCatalog {

    /**
     * Load the applicable state labor standard for the given query. In most
     * cases this will look up by [LaborStandardsQuery.workState] and
     * [LaborStandardsQuery.asOfDate], and return the single matching
     * [StateLaborStandard] record, or null if none is defined.
     */
    fun loadStateStandard(query: LaborStandardsQuery): StateLaborStandard?

    /**
     * Bulk listing API primarily for administrative and synchronization use
     * (e.g., to backfill from DOL 50-state tables). Not intended for
     * per-paycheck hot-path use.
     */
    fun listStateStandards(asOfDate: LocalDate? = null): List<StateLaborStandard>
}
