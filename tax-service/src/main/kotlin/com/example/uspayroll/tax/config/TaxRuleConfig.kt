package com.example.uspayroll.tax.config

import java.time.LocalDate

/**
 * Configuration model for tax rules as represented in Git-managed config
 * files. These are higher-level than the raw TaxRuleRecord/DB representation
 * and are translated into SCD2 rows in the `tax_rule` table.
 */
data class TaxRuleFile(
    val rules: List<TaxRuleConfig> = emptyList(),
)

/**
 * One tax rule as described in configuration.
 */
data class TaxRuleConfig(
    val id: String,
    val jurisdictionType: String,
    val jurisdictionCode: String,
    val basis: String,
    val ruleType: String,
    val rate: Double? = null,
    val annualWageCapCents: Long? = null,
    val brackets: List<TaxBracketConfig>? = null,
    val standardDeductionCents: Long? = null,
    val additionalWithholdingCents: Long? = null,
    val employerId: String? = null,
    val effectiveFrom: LocalDate,
    val effectiveTo: LocalDate,
    val filingStatus: String? = null,
    val residentStateFilter: String? = null,
    val workStateFilter: String? = null,
    val localityFilter: String? = null,
)

/**
 * Bracket configuration for bracketed tax rules. This will be serialized into
 * brackets_json for storage in the `tax_rule` table.
 */
data class TaxBracketConfig(
    val upToCents: Long?,
    val rate: Double,
    /**
     * Optional fixed tax amount for wage-bracket style rules. For
     * `ruleType = "WAGE_BRACKET"`, this field is required and represents the
     * tax to apply when the basis falls within this bracket's range.
     *
     * For `FLAT` and `BRACKETED` rules this is ignored and may be null.
     */
    val taxCents: Long? = null,
)
