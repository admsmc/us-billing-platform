package com.example.uspayroll.payroll.model

import com.example.uspayroll.shared.Money

// Tax model

enum class TaxJurisdictionType { FEDERAL, STATE, LOCAL, OTHER }

data class TaxJurisdiction(
    val type: TaxJurisdictionType,
    val code: String, // e.g. "US", "CA", "NYC"
)

sealed class TaxBasis {
    object Gross : TaxBasis()
    object FederalTaxable : TaxBasis()
    object StateTaxable : TaxBasis()
    object SocialSecurityWages : TaxBasis()
    object MedicareWages : TaxBasis()

    /**
     * Wages that are considered supplemental (bonuses, commissions, certain
     * non-regular payments) and may be subject to special flat supplemental
     * tax rules.
     */
    object SupplementalWages : TaxBasis()

    /**
     * Wages subject to FUTA (federal unemployment) taxation. For now this is
     * modeled as a separate basis so that FUTA rules can use their own wage
     * base caps and reporting semantics.
     */
    object FutaWages : TaxBasis()
}

sealed class TaxRule {
    abstract val id: String
    abstract val jurisdiction: TaxJurisdiction
    abstract val basis: TaxBasis

    data class BracketedIncomeTax(
        override val id: String,
        override val jurisdiction: TaxJurisdiction,
        override val basis: TaxBasis,
        val brackets: List<TaxBracket>,
        val standardDeduction: Money? = null,
        val additionalWithholding: Money? = null,
        /**
         * Optional filing status selector for this rule. When non-null, the
         * rule applies only to employees with the matching [FilingStatus]. When
         * null, the rule is treated as generic and may apply to all statuses.
         */
        val filingStatus: FilingStatus? = null,
    ) : TaxRule()

    data class FlatRateTax(
        override val id: String,
        override val jurisdiction: TaxJurisdiction,
        override val basis: TaxBasis,
        val rate: Percent,
        val annualWageCap: Money? = null,
    ) : TaxRule()

    /**
     * Table-based wage-bracket tax: for a given basis amount, select the first
     * bracket whose `upTo` bound contains the amount and apply its fixed
     * [WageBracketRow.tax] amount (plus any additional withholding).
     */
    data class WageBracketTax(
        override val id: String,
        override val jurisdiction: TaxJurisdiction,
        override val basis: TaxBasis,
        val brackets: List<WageBracketRow>,
        val filingStatus: FilingStatus? = null,
    ) : TaxRule()
}

data class TaxBracket(
    val upTo: Money?, // null = no upper bound
    val rate: Percent,
)

/** Row in a wage-bracket tax table. */
data class WageBracketRow(
    val upTo: Money?, // null = no upper bound
    val tax: Money,
)

data class TaxContext(
    val federal: List<TaxRule> = emptyList(),
    val state: List<TaxRule> = emptyList(),
    val local: List<TaxRule> = emptyList(),
    val employerSpecific: List<TaxRule> = emptyList(),
)
