package com.example.usbilling.tax.impl

import com.example.usbilling.payroll.model.TaxBasis
import com.example.usbilling.payroll.model.TaxJurisdictionType
import com.example.usbilling.shared.EmployerId
import com.example.usbilling.tax.api.TaxQuery
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class CatalogBackedTaxContextProviderTest {

    @Test
    fun `partitions rules into TaxContext buckets by jurisdiction type`() {
        val employerId = EmployerId("EMP123")
        val asOfDate = LocalDate.of(2025, 1, 15)

        val inMemoryRepository = object : TaxRuleRepository {
            override fun findRulesFor(query: TaxQuery): List<TaxRuleRecord> {
                // We ignore the query for this unit test and just return
                // a fixed set of rules across all jurisdiction types.
                return listOf(
                    TaxRuleRecord(
                        id = "FED_INCOME",
                        jurisdictionType = TaxJurisdictionType.FEDERAL,
                        jurisdictionCode = "US",
                        basis = TaxBasis.FederalTaxable,
                        ruleType = TaxRuleRecord.RuleType.FLAT,
                        rate = 0.10,
                        annualWageCapCents = null,
                        bracketsJson = null,
                        standardDeductionCents = null,
                        additionalWithholdingCents = null,
                    ),
                    TaxRuleRecord(
                        id = "CA_INCOME",
                        jurisdictionType = TaxJurisdictionType.STATE,
                        jurisdictionCode = "CA",
                        basis = TaxBasis.StateTaxable,
                        ruleType = TaxRuleRecord.RuleType.FLAT,
                        rate = 0.05,
                        annualWageCapCents = null,
                        bracketsJson = null,
                        standardDeductionCents = null,
                        additionalWithholdingCents = null,
                    ),
                    TaxRuleRecord(
                        id = "SF_LOCAL",
                        jurisdictionType = TaxJurisdictionType.LOCAL,
                        jurisdictionCode = "SF",
                        basis = TaxBasis.Gross,
                        ruleType = TaxRuleRecord.RuleType.FLAT,
                        rate = 0.01,
                        annualWageCapCents = null,
                        bracketsJson = null,
                        standardDeductionCents = null,
                        additionalWithholdingCents = null,
                    ),
                    TaxRuleRecord(
                        id = "ER_FUTA",
                        jurisdictionType = TaxJurisdictionType.OTHER,
                        jurisdictionCode = "US-ER",
                        basis = TaxBasis.Gross,
                        ruleType = TaxRuleRecord.RuleType.FLAT,
                        rate = 0.006,
                        annualWageCapCents = 700000L,
                        bracketsJson = null,
                        standardDeductionCents = null,
                        additionalWithholdingCents = null,
                    ),
                )
            }
        }

        val catalog = DbTaxCatalog(inMemoryRepository)
        val provider = CatalogBackedTaxContextProvider(catalog)

        val taxContext = provider.getTaxContext(employerId, asOfDate)

        assertEquals(1, taxContext.federal.size, "Expected one federal rule")
        assertEquals("FED_INCOME", taxContext.federal.single().id)

        assertEquals(1, taxContext.state.size, "Expected one state rule")
        assertEquals("CA_INCOME", taxContext.state.single().id)

        assertEquals(1, taxContext.local.size, "Expected one local rule")
        assertEquals("SF_LOCAL", taxContext.local.single().id)

        assertEquals(1, taxContext.employerSpecific.size, "Expected one employer-specific rule")
        assertEquals("ER_FUTA", taxContext.employerSpecific.single().id)
    }

    @Test
    fun `uses cached TaxContext for repeated calls with same employer and date`() {
        val employerId = EmployerId("EMP456")
        val asOfDate = LocalDate.of(2025, 6, 30)

        class CountingTaxRuleRepository : TaxRuleRepository {
            var calls: Int = 0

            override fun findRulesFor(query: TaxQuery): List<TaxRuleRecord> {
                calls += 1
                return listOf(
                    TaxRuleRecord(
                        id = "FED_ONLY",
                        jurisdictionType = TaxJurisdictionType.FEDERAL,
                        jurisdictionCode = "US",
                        basis = TaxBasis.Gross,
                        ruleType = TaxRuleRecord.RuleType.FLAT,
                        rate = 0.10,
                        annualWageCapCents = null,
                        bracketsJson = null,
                        standardDeductionCents = null,
                        additionalWithholdingCents = null,
                    ),
                )
            }
        }

        val repository = CountingTaxRuleRepository()
        val catalog = DbTaxCatalog(repository)
        val cachingCatalog = CachingTaxCatalog(catalog)
        val provider = CatalogBackedTaxContextProvider(cachingCatalog)

        // Call multiple times with the same employer/date; underlying repository
        // should be hit only once thanks to the cache.
        val taxContext1 = provider.getTaxContext(employerId, asOfDate)
        val taxContext2 = provider.getTaxContext(employerId, asOfDate)

        assertEquals(1, repository.calls, "Expected tax rules to be loaded only once for the same employer/date")
        assertEquals(taxContext1, taxContext2, "Expected identical TaxContext results from cached catalog")
        assertEquals(1, taxContext1.federal.size, "Expected one federal rule from cached catalog")
        assertEquals("FED_ONLY", taxContext1.federal.single().id)
    }
}
