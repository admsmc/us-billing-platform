package com.example.uspayroll.tax.persistence

import com.example.uspayroll.tax.api.TaxQuery
import com.example.uspayroll.tax.impl.TaxRuleRecord
import com.example.uspayroll.tax.impl.TaxRuleRepository
import org.springframework.stereotype.Repository

/**
 * Temporary stub implementation of [TaxRuleRepository].
 *
 * This allows Spring to construct a functioning application context while the
 * real database-backed implementation is designed. Callers should replace this
 * with a JDBC/jOOQ/Spring Data based repository in a future iteration.
 */
@Repository
@Deprecated("Replaced by JooqTaxRuleRepository; kept temporarily for reference.")
class StubTaxRuleRepository : TaxRuleRepository {

    override fun findRulesFor(query: TaxQuery): List<TaxRuleRecord> {
        // Legacy stub; prefer JooqTaxRuleRepository wired via TaxServiceConfig.
        return emptyList()
    }
}
