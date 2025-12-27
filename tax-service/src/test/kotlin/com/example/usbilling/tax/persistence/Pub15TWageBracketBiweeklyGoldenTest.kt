package com.example.usbilling.tax.persistence

import com.example.usbilling.payroll.model.*
import com.example.usbilling.shared.UtilityId
import com.example.usbilling.tax.impl.CachingTaxCatalog
import com.example.usbilling.tax.impl.CatalogBackedTaxContextProvider
import com.example.usbilling.tax.impl.DbTaxCatalog
import com.example.usbilling.tax.impl.TaxRuleRepository
import com.example.usbilling.tax.support.H2TaxTestSupport
import com.example.usbilling.tax.support.H2TaxTestSupport.H2TaxRuleRepository
import org.jooq.DSLContext
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Golden tests for a Pub 15-T style wage-bracket biweekly FIT config encoded in
 * `federal-2025-pub15t-wage-bracket-biweekly.json`. This uses the WAGE_BRACKET
 * rule type with biweekly wage bands and fixed tax amounts per band.
 */
class Pub15TWageBracketBiweeklyGoldenTest {

    private fun createDslContext(dbName: String): DSLContext = H2TaxTestSupport.createDslContext(dbName)

    private fun importConfig(dsl: DSLContext) {
        H2TaxTestSupport.importConfigFromResource(
            dsl,
            "tax-config/federal-2025-pub15t-wage-bracket-biweekly.json",
            javaClass.classLoader,
        )
    }

    @Test
    fun `biweekly wage-bracket FIT rules are present and structurally consistent`() {
        val dsl = createDslContext("taxdb-pub15t-wb-structure")
        importConfig(dsl)

        val repository: TaxRuleRepository = H2TaxRuleRepository(dsl)
        val dbCatalog = DbTaxCatalog(repository)
        val cachingCatalog = CachingTaxCatalog(dbCatalog)
        val provider = CatalogBackedTaxContextProvider(cachingCatalog)

        val employerId = UtilityId("EMP-PUB15T-WB")
        val asOfDate = LocalDate.of(2025, 6, 30)

        val taxContext = provider.getTaxContext(employerId, asOfDate)

        val wageRules = taxContext.federal.filterIsInstance<TaxRule.WageBracketTax>()
        assertTrue(wageRules.isNotEmpty(), "Expected at least one federal WAGE_BRACKET rule in tax context")

        // The IRS Pub. 15-T wage-bracket tables for biweekly periods have many
        // narrow wage bands; ensure we loaded more than a trivial number of
        // brackets for each filing status present.
        wageRules.forEach { rule ->
            assertTrue(rule.brackets.size > 10, "Expected multiple biweekly wage brackets for ${rule.id}")
        }

        // Structural checks: upTo values must be strictly increasing where
        // non-null, and the last bracket must be open-ended (upTo == null).
        fun assertMonotonicWithOpenEnded(rule: TaxRule.WageBracketTax) {
            val amounts = rule.brackets.map { it.upTo?.amount }
            val finite = amounts.filterNotNull()
            assertTrue(
                finite.zipWithNext().all { (a, b) -> a < b },
                "Expected strictly increasing upToCents for ${rule.id}",
            )
            assertTrue(
                amounts.any { it == null },
                "Expected at least one open-ended bracket for ${rule.id}",
            )
        }

        wageRules.forEach { assertMonotonicWithOpenEnded(it) }

        // Ensure taxes are non-decreasing across brackets for at least one
        // canonical rule (pick the first by ID for determinism).
        val canonical = wageRules.sortedBy { it.id }.first()
        val canonicalTaxes = canonical.brackets.map { it.tax.amount }
        assertTrue(canonicalTaxes.zipWithNext().all { (a, b) -> a <= b })
    }
}
