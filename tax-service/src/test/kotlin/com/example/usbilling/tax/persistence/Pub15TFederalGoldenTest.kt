package com.example.usbilling.tax.persistence

import com.example.usbilling.payroll.engine.TaxesCalculator
import com.example.usbilling.payroll.model.*
import com.example.usbilling.shared.EmployeeId
import com.example.usbilling.shared.EmployerId
import com.example.usbilling.shared.Money
import com.example.usbilling.shared.PayRunId
import com.example.usbilling.shared.PaycheckId
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
 * Golden tests for the more "official" Pub 15-T style FIT config in
 * `federal-2025-pub15t.json`. These complement the smaller
 * example-federal-2025.json demo config.
 */
class Pub15TFederalGoldenTest {

    private fun createDslContext(dbName: String): DSLContext = H2TaxTestSupport.createDslContext(dbName)

    private fun importConfig(dsl: DSLContext) {
        H2TaxTestSupport.importConfigFromResource(
            dsl,
            "tax-config/federal-2025-pub15t.json",
            javaClass.classLoader,
        )
    }

    @Test
    fun `Pub 15-T FIT rules are present and structurally consistent`() {
        val dsl = createDslContext("taxdb-pub15t-structure")
        importConfig(dsl)

        val repository: TaxRuleRepository = H2TaxRuleRepository(dsl)
        val dbCatalog = DbTaxCatalog(repository)
        val cachingCatalog = CachingTaxCatalog(dbCatalog)
        val provider = CatalogBackedTaxContextProvider(cachingCatalog)

        val employerId = EmployerId("EMP-PUB15T")
        val asOfDate = LocalDate.of(2025, 6, 30)

        val taxContext = provider.getTaxContext(employerId, asOfDate)

        val rules = taxContext.federal.filterIsInstance<TaxRule.BracketedIncomeTax>()
        val ids = rules.map { it.id }.toSet()

        assertTrue("US_FED_FIT_2025_PUB15T_SINGLE" in ids)
        assertTrue("US_FED_FIT_2025_PUB15T_MARRIED" in ids)
        assertTrue("US_FED_FIT_2025_PUB15T_HOH" in ids)

        fun find(id: String) = rules.first { it.id == id }

        val single = find("US_FED_FIT_2025_PUB15T_SINGLE")
        val married = find("US_FED_FIT_2025_PUB15T_MARRIED")
        val hoh = find("US_FED_FIT_2025_PUB15T_HOH")

        assertEquals(7, single.brackets.size)
        assertEquals(7, married.brackets.size)
        assertEquals(7, hoh.brackets.size)

        // Check top marginal rate and a couple of key thresholds to match IRS 2025 tables.
        assertEquals(0.37, single.brackets.last().rate.value, 1e-9)
        assertEquals(0.37, married.brackets.last().rate.value, 1e-9)
        assertEquals(0.37, hoh.brackets.last().rate.value, 1e-9)

        assertEquals(1_192_500L, single.brackets[0].upTo?.amount, "SINGLE 10% threshold should be $11,925")
        assertEquals(4_847_500L, single.brackets[1].upTo?.amount, "SINGLE 12% upper bound should be $48,475")
        assertEquals(2_385_000L, married.brackets[0].upTo?.amount, "MARRIED 10% threshold should be $23,850")
        assertEquals(9_695_000L, married.brackets[1].upTo?.amount, "MARRIED 12% upper bound should be $96,950")
        assertEquals(1_700_000L, hoh.brackets[0].upTo?.amount, "HOH 10% threshold should be $17,000")
        assertEquals(6_485_000L, hoh.brackets[1].upTo?.amount, "HOH 12% upper bound should be $64,850")
    }

    @Test
    fun `Pub 15-T FIT produces monotonic tax across incomes for SINGLE`() {
        val dsl = createDslContext("taxdb-pub15t-single")
        importConfig(dsl)

        val repository: TaxRuleRepository = H2TaxRuleRepository(dsl)
        val dbCatalog = DbTaxCatalog(repository)
        val cachingCatalog = CachingTaxCatalog(dbCatalog)
        val provider = CatalogBackedTaxContextProvider(cachingCatalog)

        val employerId = EmployerId("EMP-PUB15T-SINGLE")
        val asOfDate = LocalDate.of(2025, 6, 30)

        val taxContext = provider.getTaxContext(employerId, asOfDate)

        fun compute(wagesCents: Long): Long {
            val bases: Map<TaxBasis, Money> = mapOf(
                TaxBasis.FederalTaxable to Money(wagesCents),
            )
            val basisComponents: Map<TaxBasis, Map<String, Money>> = mapOf(
                TaxBasis.FederalTaxable to mapOf("federalTaxable" to Money(wagesCents)),
            )

            val period = PayPeriod(
                id = "PUB15T-SINGLE-$wagesCents",
                employerId = employerId,
                dateRange = LocalDateRange(asOfDate, asOfDate),
                checkDate = asOfDate,
                frequency = PayFrequency.ANNUAL,
            )

            val snapshot = EmployeeSnapshot(
                employerId = employerId,
                employeeId = EmployeeId("EE-PUB15T-SINGLE-$wagesCents"),
                homeState = "CA",
                workState = "CA",
                filingStatus = FilingStatus.SINGLE,
                baseCompensation = BaseCompensation.Salaried(
                    annualSalary = Money(wagesCents),
                    frequency = PayFrequency.ANNUAL,
                ),
            )

            val input = PaycheckInput(
                paycheckId = PaycheckId("CHK-PUB15T-SINGLE-$wagesCents"),
                payRunId = PayRunId("RUN-PUB15T-SINGLE"),
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

            val fitLine = result.employeeTaxes.firstOrNull { it.ruleId == "US_FED_FIT_2025_PUB15T_SINGLE" }
                ?: error("Expected US_FED_FIT_2025_PUB15T_SINGLE tax line")

            return fitLine.amount.amount
        }

        val tax30k = compute(3_000_000L)
        val tax50k = compute(5_000_000L)
        val tax120k = compute(12_000_000L)

        assertTrue(tax30k < tax50k, "Pub15T SINGLE 30k < 50k")
        assertTrue(tax50k < tax120k, "Pub15T SINGLE 50k < 120k")
    }
}
