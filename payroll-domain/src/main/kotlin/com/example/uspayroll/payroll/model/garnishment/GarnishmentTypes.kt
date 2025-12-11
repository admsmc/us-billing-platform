package com.example.uspayroll.payroll.model.garnishment

import com.example.uspayroll.payroll.model.FilingStatus
import com.example.uspayroll.payroll.model.Percent
import com.example.uspayroll.payroll.model.TaxJurisdiction
import com.example.uspayroll.shared.Money
import java.time.LocalDate

/**
 * High-level categories of garnishment orders. These are domain-level
 * semantics; mapping from statutes or agency codes into these values is
 * handled by services at the boundary.
 */
enum class GarnishmentType {
    CHILD_SUPPORT,
    FEDERAL_TAX_LEVY,
    STATE_TAX_LEVY,
    STUDENT_LOAN,
    CREDITOR_GARNISHMENT,
    BANKRUPTCY,
    OTHER,
}

@JvmInline
value class GarnishmentOrderId(val value: String)

/**
 * How a particular order computes its requested amount from disposable income
 * or other bases. Jurisdiction-specific content is converted into one of
 * these primitives by upstream services.
 */
sealed class GarnishmentFormula {
    data class PercentOfDisposable(val percent: Percent) : GarnishmentFormula()

    data class FixedAmountPerPeriod(val amount: Money) : GarnishmentFormula()

    data class LesserOfPercentOrAmount(
        val percent: Percent,
        val amount: Money,
    ) : GarnishmentFormula()

    /**
     * Levy-style formula that applies exemption bands to disposable income.
     * Each band specifies an exempt amount for a disposable-income range.
     */
    data class LevyWithBands(
        val bands: List<LevyBand>,
    ) : GarnishmentFormula()
}

/**
 * One exemption band for a levy: up to [upToCents], [exemptCents] of
 * disposable income is exempt from the levy. When [upToCents] is null, the
 * band applies to all remaining income. When [filingStatus] is non-null, the
 * band applies only to employees with that filing status; null means "all".
 */
data class LevyBand(
    val upToCents: Long?,
    val exemptCents: Long,
    val filingStatus: FilingStatus? = null,
)

/**
 * Protected earnings rules that limit how far garnishments can reduce net
 * cash pay in a given paycheck.
 */
sealed class ProtectedEarningsRule {
    /** Never reduce net cash below this fixed floor for the paycheck. */
    data class FixedFloor(val amount: Money) : ProtectedEarningsRule()

    /**
     * Floor expressed as a multiple of an hourly rate times hours. Common for
     * CCPA-style federal limits.
     */
    data class MultipleOfMinWage(
        val hourlyRate: Money,
        val hours: Double,
        val multiplier: Double,
    ) : ProtectedEarningsRule()
}

/**
 * Employee-specific garnishment order metadata. Persistence and lifecycle
 * live in services; the domain uses this purely for calculation.
 */
data class GarnishmentOrder(
    val orderId: GarnishmentOrderId,
    val planId: String,
    val type: GarnishmentType,
    val issuingJurisdiction: TaxJurisdiction? = null,
    val caseNumber: String? = null,
    val servedDate: LocalDate? = null,
    val endDate: LocalDate? = null,
    /** Lower numbers indicate higher priority among orders. */
    val priorityClass: Int = 0,
    /** Tie-breaker within the same priority class. */
    val sequenceWithinClass: Int = 0,
    val formula: GarnishmentFormula,
    val protectedEarningsRule: ProtectedEarningsRule? = null,
    /**
     * Optional arrears balance as of the start of this paycheck. The domain
     * does not update this value; services remain the source of truth.
     */
    val arrearsBefore: Money? = null,
    /** Optional lifetime cap for this order across its lifecycle. */
    val lifetimeCap: Money? = null,
)

/**
 * All active garnishment orders to consider for a paycheck.
 */
data class GarnishmentContext(
    val orders: List<GarnishmentOrder> = emptyList(),
)
