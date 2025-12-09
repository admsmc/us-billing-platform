package com.example.uspayroll.tax.impl

import com.example.uspayroll.payroll.model.TaxRule
import com.example.uspayroll.tax.api.TaxCatalog
import com.example.uspayroll.tax.api.TaxQuery
import java.util.concurrent.ConcurrentHashMap

/**
 * Normalized cache key for tax rule lookups.
 *
 * For performance, we intentionally cache at a coarse granularity:
 * - employer + tax year
 * - high-level jurisdiction dimensions (resident/work state, localities)
 *
 * This ensures that repeated calls for the same effective context reuse the
 * same rule set instead of hitting the underlying repository/DB.
 */
data class TaxQueryKey(
    val employerId: String,
    val asOfYear: Int,
    val residentState: String?,
    val workState: String?,
    val localJurisdictions: Set<String>,
) {
    companion object {
        fun from(query: TaxQuery): TaxQueryKey = TaxQueryKey(
            employerId = query.employerId.value,
            asOfYear = query.asOfDate.year,
            residentState = query.residentState,
            workState = query.workState,
            localJurisdictions = query.localJurisdictions.toSet(),
        )
    }
}

/**
 * Caching decorator for [TaxCatalog].
 *
 * This is where we enforce the performance property that tax rules are loaded
 * from the underlying store at most once per normalized [TaxQueryKey] and then
 * reused from memory across many paycheck computations.
 */
class CachingTaxCatalog(
    private val delegate: TaxCatalog,
) : TaxCatalog {

    private val cache = ConcurrentHashMap<TaxQueryKey, List<TaxRule>>()

    override fun loadRules(query: TaxQuery): List<TaxRule> {
        val key = TaxQueryKey.from(query)
        return cache.computeIfAbsent(key) { delegate.loadRules(query) }
    }
}
