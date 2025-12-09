package com.example.uspayroll.tax.api

import com.example.uspayroll.shared.EmployerId
import com.example.uspayroll.payroll.model.TaxRule
import java.time.LocalDate

/**
 * Query for loading tax rules from the catalog.
 *
 * In future iterations this can be extended with employee-level attributes
 * such as resident state, work state, locality, filing status, etc.
 */
data class TaxQuery(
    val employerId: EmployerId,
    val asOfDate: LocalDate,
    val residentState: String? = null,
    val workState: String? = null,
    val localJurisdictions: List<String> = emptyList(),
)

/**
 * Catalog of tax rules backed by a data store (e.g., database or configuration).
 *
 * This boundary does not know about payroll; it simply returns [TaxRule] definitions
 * that are valid for a given [TaxQuery].
 */
interface TaxCatalog {
    fun loadRules(query: TaxQuery): List<TaxRule>
}
