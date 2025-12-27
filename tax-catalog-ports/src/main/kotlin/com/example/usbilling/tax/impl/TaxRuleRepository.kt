package com.example.usbilling.tax.impl

import com.example.usbilling.tax.api.TaxQuery

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
