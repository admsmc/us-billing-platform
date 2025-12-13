package com.example.uspayroll.tax.impl

import com.example.uspayroll.payroll.model.TaxContext
import com.example.uspayroll.payroll.model.TaxJurisdictionType
import com.example.uspayroll.shared.EmployerId
import com.example.uspayroll.tax.api.TaxCatalog
import com.example.uspayroll.tax.api.TaxContextProvider
import com.example.uspayroll.tax.api.TaxQuery
import java.time.LocalDate

/**
 * [TaxContextProvider] implementation that sources tax rules from a [TaxCatalog].
 *
 * This forms the adapter layer between the tax-service's catalog/persistence
 * and the payroll-domain tax engine. The catalog is responsible for loading all
 * applicable [com.example.uspayroll.payroll.model.TaxRule]s given a [TaxQuery];
 * here we simply partition them into the existing [TaxContext] buckets.
 */
class CatalogBackedTaxContextProvider(
    private val catalog: TaxCatalog,
) : TaxContextProvider {

    override fun getTaxContext(employerId: EmployerId, asOfDate: LocalDate): TaxContext {
        // In a later iteration, this query can be enriched with employee-level
        // state/locality and filing information.
        val query = TaxQuery(
            employerId = employerId,
            asOfDate = asOfDate,
        )

        val rules = catalog.loadRules(query)

        val federal = rules.filter { it.jurisdiction.type == TaxJurisdictionType.FEDERAL }
        val state = rules.filter { it.jurisdiction.type == TaxJurisdictionType.STATE }
        val local = rules.filter { it.jurisdiction.type == TaxJurisdictionType.LOCAL }
        val employerSpecific = rules.filter { it.jurisdiction.type == TaxJurisdictionType.OTHER }

        return TaxContext(
            federal = federal,
            state = state,
            local = local,
            employerSpecific = employerSpecific,
        )
    }
}
