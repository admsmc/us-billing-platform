package com.example.uspayroll.tax.persistence

import com.example.uspayroll.payroll.engine.TaxesCalculator
import com.example.uspayroll.payroll.model.*
import com.example.uspayroll.shared.EmployeeId
import com.example.uspayroll.shared.EmployerId
import com.example.uspayroll.shared.Money
import com.example.uspayroll.shared.PaycheckId
import com.example.uspayroll.shared.PayRunId
import com.example.uspayroll.tax.impl.CachingTaxCatalog
import com.example.uspayroll.tax.impl.CatalogBackedTaxContextProvider
import com.example.uspayroll.tax.impl.DbTaxCatalog
import com.example.uspayroll.tax.impl.TaxRuleRepository
import com.example.uspayroll.tax.support.H2TaxTestSupport
import com.example.uspayroll.tax.support.H2TaxTestSupport.H2TaxRuleRepository
import org.jooq.DSLContext
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Golden tests for a Pub 15-T style wage-bracket biweekly FIT config encoded in
 * `federal-2025-pub15t-wage-bracket-biweekly.json`. This uses the WAGE_BRACKET
 * rule type with biweekly wage bands and fixed tax amounts per band.
 */
class Pub15TWageBracketBiweeklyGoldenTest {

    private fun createDslContext(dbName: String): DSLContext =
        H2TaxTestSupport.createDslContext(dbName)

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

        val employerId = EmployerId("EMP-PUB15T-WB")
        val asOfDate = LocalDate.of(2025, 6, 30)

        val taxContext = provider.getTaxContext(employerId, asOfDate)

        val rules = taxContext.federal.filterIsInstance<TaxRule.WageBracketTax>()
        val ids = rules.map { it.id }.toSet()

        assertTrue("US_FED_FIT_2025_PUB15T_WB_SINGLE_BI" in ids)
        assertTrue("US_FED_FIT_2025_PUB15T_WB_MARRIED_BI" in ids)
        assertTrue("US_FED_FIT_2025_PUB15T_WB_HOH_BI" in ids)

        fun find(id: String) = rules.first { it.id == id }

        val single = find("US_FED_FIT_2025_PUB15T_WB_SINGLE_BI")
        val married = find("US_FED_FIT_2025_PUB15T_WB_MARRIED_BI")
        val hoh = find("US_FED_FIT_2025_PUB15T_WB_HOH_BI")

        assertEquals(8, single.brackets.size)
        assertEquals(8, married.brackets.size)
        assertEquals(8, hoh.brackets.size)

        // Check a couple of key thresholds to ensure wage bands are wired correctly.
        assertEquals(80_000L, single.brackets[0].upTo?.amount)
        assertEquals(400_000L, single.brackets[1].upTo?.amount)
        assertEquals(160_000L, married.brackets[0].upTo?.amount)
        assertEquals(800_000L, married.brackets[1].upTo?.amount)

        // Ensure taxes are non-decreasing across brackets for SINGLE.
        val singleTaxes = single.brackets.map { it.tax.amount }
        assertTrue(singleTaxes.zipWithNext().all { (a, b) -> a <= b })
    }

    @Test
    fun `biweekly wage-bracket FIT produces monotonic tax across wages for SINGLE`() {
        val dsl = createDslContext("taxdb-pub15t-wb-single")
        importConfig(dsl)

        val repository: TaxRuleRepository = H2TaxRuleRepository(dsl)
        val dbCatalog = DbTaxCatalog(repository)
        val cachingCatalog = CachingTaxCatalog(dbCatalog)
        val provider = CatalogBackedTaxContextProvider(cachingCatalog)

        val employerId = EmployerId("EMP-PUB15T-WB-SINGLE")
        val asOfDate = LocalDate.of(2025, 6, 30)

        val taxContext = provider.getTaxContext(employerId, asOfDate)

        fun computeBiweeklyFit(wagesPerPeriodCents: Long): Long {
            // For wage-bracket rules, tables are defined directly on per-period
            // FederalTaxable wages, so we use the biweekly amount as the basis.
            val bases: Map<TaxBasis, Money> = mapOf(
                TaxBasis.FederalTaxable to Money(wagesPerPeriodCents),
            )
            val basisComponents: Map<TaxBasis, Map<String, Money>> = mapOf(
                TaxBasis.FederalTaxable to mapOf("federalTaxable" to Money(wagesPerPeriodCents)),
            )

            val period = PayPeriod(
                id = "PUB15T-WB-SINGLE-$wagesPerPeriodCents",
                employerId = employerId,
                dateRange = LocalDateRange(asOfDate, asOfDate),
                checkDate = asOfDate,
                frequency = PayFrequency.BIWEEKLY,
            )

            val snapshot = EmployeeSnapshot(
                employerId = employerId,
                employeeId = EmployeeId("EE-PUB15T-WB-SINGLE-$wagesPerPeriodCents"),
                homeState = "CA",
                workState = "CA",
                filingStatus = FilingStatus.SINGLE,
                baseCompensation = BaseCompensation.Salaried(
                    annualSalary = Money(wagesPerPeriodCents * 26),
                    frequency = PayFrequency.BIWEEKLY,
                ),
            )

            val input = PaycheckInput(
                paycheckId = PaycheckId("CHK-PUB15T-WB-SINGLE-$wagesPerPeriodCents"),
                payRunId = PayRunId("RUN-PUB15T-WB-SINGLE"),
                employerId = employerId,
                employeeId = snapshot.employeeId,
                period = period,
                employeeSnapshot = snapshot,
                timeSlice = TimeSlice(
                    period = period,
                    regularHours = 0.0,
                    overtimeHours = 0.0,
                ),
                taxContext = taxContext,
                priorYtd = YtdSnapshot(year = asOfDate.year),
            )

            val result = TaxesCalculator.computeTaxes(input, bases, basisComponents)

            val fitLine = result.employeeTaxes.firstOrNull { it.ruleId == "US_FED_FIT_2025_PUB15T_WB_SINGLE_BI" }
                ?: error("Expected US_FED_FIT_2025_PUB15T_WB_SINGLE_BI tax line")

            return fitLine.amount.amount
        }

        val tax500 = computeBiweeklyFit(500_00L)
        val tax1500 = computeBiweeklyFit(1_500_00L)
        val tax3000 = computeBiweeklyFit(3_000_00L)

        assertTrue(tax500 <= tax1500, "Biweekly SINGLE 500 should owe no more than 1500")
        assertTrue(tax1500 <= tax3000, "Biweekly SINGLE 1500 should owe no more than 3000")
    }
}
