package com.example.usbilling.tax.impl

import com.example.usbilling.payroll.model.TaxRule
import com.example.usbilling.tax.api.TaxCatalog
import com.example.usbilling.tax.api.TaxQuery
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

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

    private val logger = LoggerFactory.getLogger(CachingTaxCatalog::class.java)

    private val cache = ConcurrentHashMap<TaxQueryKey, List<TaxRule>>()
    private val loadCalls = AtomicLong(0)
    private val cacheHits = AtomicLong(0)
    private val cacheMisses = AtomicLong(0)

    override fun loadRules(query: TaxQuery): List<TaxRule> {
        loadCalls.incrementAndGet()
        val key = TaxQueryKey.from(query)

        val existing = cache[key]
        if (existing != null) {
            cacheHits.incrementAndGet()
            logger.debug("TaxCatalog cache HIT for key={} (size={})", key, existing.size)
            return existing
        }

        cacheMisses.incrementAndGet()
        val loaded = delegate.loadRules(query)
        if (loaded.isNotEmpty()) {
            cache[key] = loaded
        }
        logger.debug("TaxCatalog cache MISS for key={} (loaded {} rules)", key, loaded.size)
        return loaded
    }

    data class CacheMetrics(
        val loadCalls: Long,
        val cacheHits: Long,
        val cacheMisses: Long,
        val entries: Long,
    )

    fun metricsSnapshot(): CacheMetrics = CacheMetrics(
        loadCalls = loadCalls.get(),
        cacheHits = cacheHits.get(),
        cacheMisses = cacheMisses.get(),
        entries = cache.size.toLong(),
    )
}
