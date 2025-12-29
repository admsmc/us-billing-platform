package com.example.usbilling.tax.persistence

import com.example.usbilling.payroll.engine.TaxesCalculator
import com.example.usbilling.payroll.model.*
import com.example.usbilling.shared.BillId
import com.example.usbilling.shared.BillingCycleId
import com.example.usbilling.shared.CustomerId
import com.example.usbilling.shared.Money
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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Golden tests for frequency-specific Pub 15-T-style FIT configs, starting with
 * a weekly bracketed schedule encoded in `federal-2025-pub15t-weekly.json`.
 */
class Pub15TFrequencySpecificGoldenTest {

    private fun createDslContext(dbName: String): DSLContext = H2TaxTestSupport.createDslContext(dbName)

    private fun importWeeklyConfig(dsl: DSLContext) {
        H2TaxTestSupport.importConfigFromResource(
            dsl,
            "tax-config/federal-2025-pub15t-weekly.json",
            javaClass.classLoader,
        )
    }

    @Test
    fun `weekly FIT rules are present and structurally consistent`() {
        val dsl = createDslContext("taxdb-pub15t-weekly-structure")
        importWeeklyConfig(dsl)

        val repository: TaxRuleRepository = H2TaxRuleRepository(dsl)
        val dbCatalog = DbTaxCatalog(repository)
        val cachingCatalog = CachingTaxCatalog(dbCatalog)
        val provider = CatalogBackedTaxContextProvider(cachingCatalog)

        val employerId = UtilityId("EMP-PUB15T-WEEKLY")
        val asOfDate = LocalDate.of(2025, 6, 30)

        val taxContext = provider.getTaxContext(employerId, asOfDate)

        val rules = taxContext.federal.filterIsInstance<TaxRule.BracketedIncomeTax>()
        val ids = rules.map { it.id }.toSet()

        assertTrue("US_FED_FIT_2025_PUB15T_SINGLE_WEEKLY" in ids)
        assertTrue("US_FED_FIT_2025_PUB15T_MARRIED_WEEKLY" in ids)
        assertTrue("US_FED_FIT_2025_PUB15T_HOH_WEEKLY" in ids)

        fun find(id: String) = rules.first { it.id == id }

        val single = find("US_FED_FIT_2025_PUB15T_SINGLE_WEEKLY")
        val married = find("US_FED_FIT_2025_PUB15T_MARRIED_WEEKLY")
        val hoh = find("US_FED_FIT_2025_PUB15T_HOH_WEEKLY")

        assertEquals(7, single.brackets.size)
        assertEquals(7, married.brackets.size)
        assertEquals(7, hoh.brackets.size)

        assertEquals(0.37, single.brackets.last().rate.value, 1e-9)
        assertEquals(0.37, married.brackets.last().rate.value, 1e-9)
        assertEquals(0.37, hoh.brackets.last().rate.value, 1e-9)
    }

    @Test
    fun `weekly FIT produces monotonic tax and favorable status ordering`() {
        val dsl = createDslContext("taxdb-pub15t-weekly-mono")
        importWeeklyConfig(dsl)

        val repository: TaxRuleRepository = H2TaxRuleRepository(dsl)
        val dbCatalog = DbTaxCatalog(repository)
        val cachingCatalog = CachingTaxCatalog(dbCatalog)
        val provider = CatalogBackedTaxContextProvider(cachingCatalog)

        val employerId = UtilityId("EMP-PUB15T-WEEKLY-MONO")
        val asOfDate = LocalDate.of(2025, 6, 30)

        val taxContext = provider.getTaxContext(employerId, asOfDate)

        fun computeWeeklyFit(filingStatus: FilingStatus, weeklyWagesCents: Long): Long {
            val annualized = weeklyWagesCents * 52

            val bases: Map<TaxBasis, Money> = mapOf(
                TaxBasis.FederalTaxable to Money(annualized),
            )
            val basisComponents: Map<TaxBasis, Map<String, Money>> = mapOf(
                TaxBasis.FederalTaxable to mapOf("federalTaxable" to Money(annualized)),
            )

            val period = PayPeriod(
                id = "PUB15T-WEEKLY-${filingStatus.name}-$weeklyWagesCents",
                employerId = employerId,
                dateRange = LocalDateRange(asOfDate, asOfDate),
                checkDate = asOfDate,
                frequency = PayFrequency.WEEKLY,
            )

            val snapshot = EmployeeSnapshot(
                employerId = employerId,
                employeeId = CustomerId("EE-PUB15T-WEEKLY-${filingStatus.name}-$weeklyWagesCents"),
                homeState = "CA",
                workState = "CA",
                filingStatus = filingStatus,
                baseCompensation = BaseCompensation.Salaried(
                    annualSalary = Money(annualized),
                    frequency = PayFrequency.WEEKLY,
                ),
            )

            val input = PaycheckInput(
                paycheckId = BillId("CHK-PUB15T-WEEKLY-${filingStatus.name}-$weeklyWagesCents"),
                payRunId = BillingCycleId("RUN-PUB15T-WEEKLY"),
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

            val ruleId = when (filingStatus) {
                FilingStatus.SINGLE -> "US_FED_FIT_2025_PUB15T_SINGLE_WEEKLY"
                FilingStatus.MARRIED -> "US_FED_FIT_2025_PUB15T_MARRIED_WEEKLY"
                FilingStatus.HEAD_OF_HOUSEHOLD -> "US_FED_FIT_2025_PUB15T_HOH_WEEKLY"
            }

            val fitLine = result.employeeTaxes.firstOrNull { it.ruleId == ruleId }
                ?: error("Expected $ruleId tax line")

            return fitLine.amount.amount
        }

        val weeklyLevels = listOf(500_00L, 1_500_00L, 3_000_00L)

        // Monotonic for SINGLE.
        val singleTaxes = weeklyLevels.map { lvl -> computeWeeklyFit(FilingStatus.SINGLE, lvl) }
        assertTrue(singleTaxes[0] <= singleTaxes[1])
        assertTrue(singleTaxes[1] <= singleTaxes[2])

        // MARRIED and HOH should not owe more than SINGLE at the same wages.
        weeklyLevels.forEach { lvl ->
            val single = computeWeeklyFit(FilingStatus.SINGLE, lvl)
            val married = computeWeeklyFit(FilingStatus.MARRIED, lvl)
            val hoh = computeWeeklyFit(FilingStatus.HEAD_OF_HOUSEHOLD, lvl)

            assertTrue(married <= single)
            assertTrue(hoh <= single)
        }
    }
}
