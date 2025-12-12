package com.example.uspayroll.payroll.model

import com.example.uspayroll.shared.Money

// YTD snapshot
//
// The `year` field represents the tax year the YTD values apply to. Callers
// are responsible for supplying an appropriate snapshot for the paycheck's
// check date (for example, resetting or rolling over when moving from one
// tax year to the next).

data class YtdSnapshot(
    val year: Int,
    val earningsByCode: Map<EarningCode, Money> = emptyMap(),
    val employeeTaxesByRuleId: Map<String, Money> = emptyMap(),
    val employerTaxesByRuleId: Map<String, Money> = emptyMap(),
    val deductionsByCode: Map<DeductionCode, Money> = emptyMap(),
    val wagesByBasis: Map<TaxBasis, Money> = emptyMap(),
    val employerContributionsByCode: Map<EmployerContributionCode, Money> = emptyMap(),
) {
    /**
     * Create a fresh YTD snapshot for a new tax year, resetting all
     * accumulated amounts while preserving only the target year.
     */
    fun resetForYear(newYear: Int): YtdSnapshot = YtdSnapshot(year = newYear)
}
