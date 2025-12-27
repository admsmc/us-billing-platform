package com.example.usbilling.tax.persistence

import com.example.usbilling.payroll.engine.TaxesCalculator
import com.example.usbilling.payroll.model.*
import com.example.usbilling.shared.CustomerId
import com.example.usbilling.shared.UtilityId
import com.example.usbilling.shared.Money
import com.example.usbilling.shared.BillRunId
import com.example.usbilling.shared.BillId
import com.example.usbilling.tax.impl.CachingTaxCatalog
import com.example.usbilling.tax.impl.CatalogBackedTaxContextProvider
import com.example.usbilling.tax.impl.DbTaxCatalog
import com.example.usbilling.tax.impl.TaxRuleRepository
import com.example.usbilling.tax.support.H2TaxTestSupport
import com.example.usbilling.tax.support.H2TaxTestSupport.H2TaxRuleRepository
import org.jooq.DSLContext
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Golden tests for the WAGE_BRACKET rule type using a simple demo config.
 */
class WageBracketTaxGoldenTest {

    private fun createDslContext(dbName: String): DSLContext = H2TaxTestSupport.createDslContext(dbName)

    private fun importConfig(dsl: DSLContext) {
        H2TaxTestSupport.importConfigFromResource(
            dsl,
            "tax-config/federal-2025-wage-bracket-demo.json",
            javaClass.classLoader,
        )
    }

    @Test
    fun `WAGE_BRACKET rule is loaded as WageBracketTax from DB`() {
        val dsl = createDslContext("taxdb-wage-bracket-structure")
        importConfig(dsl)

        val repository: TaxRuleRepository = H2TaxRuleRepository(dsl)
        val dbCatalog = DbTaxCatalog(repository)
        val cachingCatalog = CachingTaxCatalog(dbCatalog)
        val provider = CatalogBackedTaxContextProvider(cachingCatalog)

        val employerId = UtilityId("EMP-WB-DEMO")
        val asOfDate = LocalDate.of(2025, 6, 30)

        val taxContext = provider.getTaxContext(employerId, asOfDate)

        val wageRules = taxContext.federal.filterIsInstance<TaxRule.WageBracketTax>()
        assertEquals(1, wageRules.size)

        val rule = wageRules.first()
        assertEquals("US_FED_FIT_2025_WB_SINGLE_DEMO", rule.id)
        assertEquals(3, rule.brackets.size)

        val taxes = rule.brackets.map { it.tax.amount }.toSet()
        assertTrue(150_000L in taxes)
        assertTrue(600_000L in taxes)
        assertTrue(1_500_000L in taxes)
    }

    @Test
    fun `WAGE_BRACKET rule applies fixed tax amounts across wage bands`() {
        val dsl = createDslContext("taxdb-wage-bracket-apply")
        importConfig(dsl)

        val repository: TaxRuleRepository = H2TaxRuleRepository(dsl)
        val dbCatalog = DbTaxCatalog(repository)
        val cachingCatalog = CachingTaxCatalog(dbCatalog)
        val provider = CatalogBackedTaxContextProvider(cachingCatalog)

        val employerId = UtilityId("EMP-WB-DEMO-APPLY")
        val asOfDate = LocalDate.of(2025, 6, 30)

        val taxContext = provider.getTaxContext(employerId, asOfDate)

        fun computeTaxFor(wagesCents: Long): Long {
            val bases: Map<TaxBasis, Money> = mapOf(
                TaxBasis.FederalTaxable to Money(wagesCents),
            )
            val basisComponents: Map<TaxBasis, Map<String, Money>> = mapOf(
                TaxBasis.FederalTaxable to mapOf("federalTaxable" to Money(wagesCents)),
            )

            val period = PayPeriod(
                id = "WB-DEMO-$wagesCents",
                employerId = employerId,
                dateRange = LocalDateRange(asOfDate, asOfDate),
                checkDate = asOfDate,
                frequency = PayFrequency.ANNUAL,
            )

            val snapshot = EmployeeSnapshot(
                employerId = employerId,
                employeeId = CustomerId("EE-WB-$wagesCents"),
                homeState = "CA",
                workState = "CA",
                filingStatus = FilingStatus.SINGLE,
                baseCompensation = BaseCompensation.Salaried(
                    annualSalary = Money(wagesCents),
                    frequency = PayFrequency.ANNUAL,
                ),
            )

            val input = PaycheckInput(
                paycheckId = BillId("CHK-WB-$wagesCents"),
                payRunId = BillRunId("RUN-WB-DEMO"),
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

            val fitLine = result.employeeTaxes.firstOrNull { it.ruleId == "US_FED_FIT_2025_WB_SINGLE_DEMO" }
                ?: error("Expected US_FED_FIT_2025_WB_SINGLE_DEMO tax line")

            return fitLine.amount.amount
        }

        val taxBelow30k = computeTaxFor(20_000_00L)
        val taxBetween30kAnd60k = computeTaxFor(45_000_00L)
        val taxAbove60k = computeTaxFor(100_000_00L)

        assertEquals(150_000L, taxBelow30k)
        assertEquals(600_000L, taxBetween30kAnd60k)
        assertEquals(1_500_000L, taxAbove60k)
    }
}
