package com.example.uspayroll.tax.persistence

import com.example.uspayroll.tax.api.TaxQuery
import com.example.uspayroll.tax.impl.TaxRuleRecord
import com.example.uspayroll.tax.impl.TaxRuleRepository

/**
 * Temporary stub implementation of [TaxRuleRepository].
 *
 * This is intentionally *not* a Spring bean anymore (the real implementation
 * is wired via [com.example.uspayroll.tax.config.TaxServiceConfig]).
 */
@Deprecated("Replaced by JooqTaxRuleRepository; kept temporarily for reference.")
class StubTaxRuleRepository : TaxRuleRepository {

    override fun findRulesFor(query: TaxQuery): List<TaxRuleRecord> {
        // Legacy stub; prefer JooqTaxRuleRepository wired via TaxServiceConfig.
        return emptyList()
    }
}
