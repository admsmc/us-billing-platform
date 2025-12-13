package com.example.uspayroll.tax.impl

import com.example.uspayroll.tax.api.TaxQuery

/**
 * Repository abstraction for retrieving tax rules from the underlying store.
 *
 * A concrete implementation will typically query a relational database using
 * [TaxQuery] fields (employer, date, jurisdiction, etc.) and effective-dated
 * tax rule tables.
 */
interface TaxRuleRepository {
    fun findRulesFor(query: TaxQuery): List<TaxRuleRecord>
}
