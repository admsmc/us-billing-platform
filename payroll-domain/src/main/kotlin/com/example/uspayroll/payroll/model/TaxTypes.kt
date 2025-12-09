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
    ) : TaxRule()

    data class FlatRateTax(
        override val id: String,
        override val jurisdiction: TaxJurisdiction,
        override val basis: TaxBasis,
        val rate: Percent,
        val annualWageCap: Money? = null,
    ) : TaxRule()
}

data class TaxBracket(
    val upTo: Money?, // null = no upper bound
    val rate: Percent,
)

data class TaxContext(
    val federal: List<TaxRule> = emptyList(),
    val state: List<TaxRule> = emptyList(),
    val local: List<TaxRule> = emptyList(),
    val employerSpecific: List<TaxRule> = emptyList(),
)
