package com.example.usbilling.tax.persistence

import com.example.usbilling.payroll.model.*
import com.example.usbilling.shared.EmployerId
import com.example.usbilling.tax.support.H2TaxTestSupport
import com.example.usbilling.tax.support.H2TaxTestSupport.H2TaxRuleRepository
import com.example.usbilling.tax.tools.WageBracketCsvParser
import org.jooq.DSLContext
import java.io.InputStreamReader
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Golden tests that lock in a small set of exact Pub. 15-T biweekly wage-bracket
 * values. These tests ensure that, for selected wage bands, the DB-backed
 * WAGE_BRACKET rules produce the same tax amounts as the IRS-derived CSV
 * (`wage-bracket-2025-biweekly.csv`).
 */
class Pub15TWageBracketBiweeklyExactValuesTest {

    private fun createDslContext(dbName: String): DSLContext = H2TaxTestSupport.createDslContext(dbName)

    private fun importConfig(dsl: DSLContext) {
        H2TaxTestSupport.importConfigFromResource(
            dsl,
            "tax-config/federal-2025-pub15t-wage-bracket-biweekly.json",
            javaClass.classLoader,
        )
    }

    private data class Case(
        val wagesPerPeriodCents: Long,
        val expectedTaxCents: Long,
        val filingStatus: FilingStatus,
        val variant: String,
    )

    /**
     * Select a few representative bands from the CSV for each filing status and
     * variant, then assert that the WAGE_BRACKET rules in the database compute
     * the same per-period tax for wages inside those bands.
     */
    @Test
    fun `DB wage-bracket rules match IRS-derived CSV for selected bands`() {
        val dsl = createDslContext("taxdb-pub15t-wb-exact")
        importConfig(dsl)

        val repository = H2TaxRuleRepository(dsl)
        val dbCatalog = com.example.uspayroll.tax.impl.DbTaxCatalog(repository)
        val provider = com.example.uspayroll.tax.impl.CatalogBackedTaxContextProvider(dbCatalog)

        val employerId = EmployerId("EMP-PUB15T-WB-EXACT")
        val asOfDate = LocalDate.of(2025, 6, 30)

        val taxContext = provider.getTaxContext(employerId, asOfDate)
        val wageRules = taxContext.federal.filterIsInstance<TaxRule.WageBracketTax>()
        require(wageRules.isNotEmpty()) { "Expected at least one federal WAGE_BRACKET rule in tax context" }

        // Parse the curated CSV and pick a small sample of rows per
        // filingStatus/variant to use as golden cases.
        val rows = javaClass.classLoader.getResourceAsStream("wage-bracket-2025-biweekly.csv")
            ?.use { stream ->
                InputStreamReader(stream).use { reader ->
                    WageBracketCsvParser.parse(reader)
                }
            }
            ?: error("Missing wage-bracket-2025-biweekly.csv on classpath")

        fun sampleCasesFor(status: String, variant: String): List<Case> {
            val filtered = rows.filter { it.filingStatus == status && it.variant == variant }
            if (filtered.isEmpty()) error("No CSV rows for status=$status variant=$variant")

            // Take first, middle, and last bands as representatives.
            val first = filtered.first()
            val middle = filtered[filtered.size / 2]
            val last = filtered.last()

            fun toCase(row: WageBracketCsvParser.Row): Case {
                val filingStatus = FilingStatus.valueOf(row.filingStatus)
                // Choose a wage inside the band; for open-ended top band, just use
                // minCents + $100 to stay within a reasonable range.
                val wages = if (row.maxCents != null) {
                    row.minCents + 100 // $1 above lower bound
                } else {
                    row.minCents + 10_000 // +$100 above lower bound
                }
                return Case(
                    wagesPerPeriodCents = wages,
                    expectedTaxCents = row.taxCents,
                    filingStatus = filingStatus,
                    variant = row.variant,
                )
            }

            return listOf(first, middle, last).map(::toCase)
        }

        val cases = buildList {
            addAll(sampleCasesFor("SINGLE", "STANDARD"))
            addAll(sampleCasesFor("SINGLE", "STEP2_CHECKBOX"))
            addAll(sampleCasesFor("MARRIED", "STANDARD"))
            addAll(sampleCasesFor("HEAD_OF_HOUSEHOLD", "STANDARD"))
        }

        fun ruleIdFor(case: Case): String {
            val base = "US_FED_FIT_2025_PUB15T_WB"
            val statusSuffix = when (case.filingStatus) {
                FilingStatus.SINGLE -> "SINGLE"
                FilingStatus.MARRIED -> "MARRIED"
                FilingStatus.HEAD_OF_HOUSEHOLD -> "HEAD_OF_HOUSEHOLD"
            }
            val variantSuffix = when (case.variant.uppercase()) {
                "STANDARD" -> "${statusSuffix}_BI"
                "STEP2_CHECKBOX" -> "${statusSuffix}_BI_STEP2"
                else -> "${statusSuffix}_BI_${case.variant.uppercase()}"
            }
            return "${base}_$variantSuffix"
        }

        fun computeTaxFor(case: Case): Long {
            val ruleId = ruleIdFor(case)
            val rule = wageRules.firstOrNull { it.id == ruleId }
                ?: error("Missing WageBracketTax rule with id=$ruleId for case=$case")

            val amount = case.wagesPerPeriodCents
            val row = rule.brackets.firstOrNull { bracket ->
                val upper = bracket.upTo?.amount ?: Long.MAX_VALUE
                amount <= upper
            } ?: error("No bracket row covering wages=$amount for ruleId=$ruleId case=$case")

            return row.tax.amount
        }

        cases.forEach { c ->
            val actual = computeTaxFor(c)
            assertEquals(
                c.expectedTaxCents,
                actual,
                "Expected DB-backed wage-bracket FIT to match CSV taxCents for case=$c",
            )
        }
    }
}
