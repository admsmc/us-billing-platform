package com.example.uspayroll.tax.persistence

import com.example.uspayroll.payroll.model.*
import com.example.uspayroll.payroll.engine.TaxesCalculator
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.time.LocalDate

/**
 * Golden H2-backed tests that assert actual tax dollar amounts for canonical
 * federal and state income tax scenarios. These tests go end-to-end through:
 *
 *   TaxRuleFile JSON -> TaxRuleConfigImporter -> tax_rule (H2) ->
 *   H2TaxRuleRepository -> DbTaxCatalog -> CachingTaxCatalog ->
 *   CatalogBackedTaxContextProvider -> TaxesCalculator
 */
class GoldenTaxScenariosTest {

    private fun createDslContext(dbName: String): DSLContext =
        H2TaxTestSupport.createDslContext(dbName)

    private fun importConfig(dsl: DSLContext, resourcePath: String) {
        H2TaxTestSupport.importConfigFromResource(dsl, resourcePath, javaClass.classLoader)
    }

    @Test
    fun `federal SINGLE golden scenario from DB-backed catalog`() {
        val dsl = createDslContext("taxdb-golden-fed")

        // Import example federal rules only; we focus on FIT here.
        importConfig(dsl, "tax-config/example-federal-2025.json")

        val repository: TaxRuleRepository = H2TaxRuleRepository(dsl)
        val dbCatalog = DbTaxCatalog(repository)
        val cachingCatalog = CachingTaxCatalog(dbCatalog)
        val provider = CatalogBackedTaxContextProvider(cachingCatalog)

        val employerId = EmployerId("EMP-FED-GOLDEN")
        val asOfDate = LocalDate.of(2025, 6, 30)

        val taxContext = provider.getTaxContext(employerId, asOfDate)

        // Sanity: ensure the expected federal FIT rule is present.
        val federalIds = taxContext.federal.map(TaxRule::id).toSet()
        assertTrue("US_FED_FIT_2025_SINGLE" in federalIds)

        // We compute expected tax using the brackets in example-federal-2025.json:
        // - Brackets for SINGLE (upToCents, rate):
        //   [11,00,00 @ 10%], [44,72,50 @ 12%], [open @ 22%]
        // - Standard deduction: 14,60,00.
        // For annual wages of 50,000.00:
        //   taxable = 50,000.00 - 14,600.00 = 35,400.00
        //   tax = 11,000.00 * 10% + 24,400.00 * 12%
        //       = 1,100.00 + 2,928.00 = 4,028.00
        val wagesCents = 5_000_000L // $50,000.00

        val bases: Map<TaxBasis, Money> = mapOf(
            TaxBasis.FederalTaxable to Money(wagesCents),
        )
        val basisComponents: Map<TaxBasis, Map<String, Money>> = mapOf(
            TaxBasis.FederalTaxable to mapOf("federalTaxable" to Money(wagesCents)),
        )

        val period = PayPeriod(
            id = "FED-GOLDEN-ANNUAL",
            employerId = employerId,
            dateRange = LocalDateRange(asOfDate, asOfDate),
            checkDate = asOfDate,
            frequency = PayFrequency.ANNUAL,
        )

        val snapshot = EmployeeSnapshot(
            employerId = employerId,
            employeeId = EmployeeId("EE-FED-GOLDEN"),
            homeState = "CA",
            workState = "CA",
            filingStatus = FilingStatus.SINGLE,
            baseCompensation = BaseCompensation.Salaried(
                annualSalary = Money(wagesCents),
                frequency = PayFrequency.ANNUAL,
            ),
        )

        val input = PaycheckInput(
            paycheckId = PaycheckId("CHK-FED-GOLDEN"),
            payRunId = PayRunId("RUN-FED-GOLDEN"),
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

        val fitLine = result.employeeTaxes.firstOrNull { it.ruleId == "US_FED_FIT_2025_SINGLE" }
            ?: error("Expected US_FED_FIT_2025_SINGLE tax line in employee taxes")

        assertEquals(402_800L, fitLine.amount.amount, "Expected annual FIT of $4,028.00 for SINGLE at $50,000")
    }

    @Test
    fun `state income golden scenario CA vs TX vs NY from DB-backed catalog`() {
        val dsl = createDslContext("taxdb-golden-state")

        // Import both federal and state rules; we'll focus on state lines.
        importConfig(dsl, "tax-config/example-federal-2025.json")
        importConfig(dsl, "tax-config/state-income-2025.json")

        val repository: TaxRuleRepository = H2TaxRuleRepository(dsl)
        val dbCatalog = DbTaxCatalog(repository)
        val cachingCatalog = CachingTaxCatalog(dbCatalog)
        val provider = CatalogBackedTaxContextProvider(cachingCatalog)

        val employerId = EmployerId("EMP-STATE-GOLDEN")
        val asOfDate = LocalDate.of(2025, 1, 15)

        val baseTaxContext = provider.getTaxContext(employerId, asOfDate)

        fun taxContextFor(stateCode: String): TaxContext = baseTaxContext.copy(
            state = baseTaxContext.state.filter {
                it.jurisdiction.type == TaxJurisdictionType.STATE && it.jurisdiction.code == stateCode
            },
        )

        // Use a simple state taxable base that lies fully in the first bracket
        // for both CA and NY SINGLE, and is taxed at 0% in TX.
        // - CA SINGLE first bracket: up to 10,756 @ 1%
        // - NY SINGLE first bracket: up to 8,500 @ 4%
        // Choose state taxable = 5,000.00 (in cents).
        val stateTaxableCents = 500_000L // $5,000.00

        val bases: Map<TaxBasis, Money> = mapOf(
            TaxBasis.StateTaxable to Money(stateTaxableCents),
        )
        val basisComponents: Map<TaxBasis, Map<String, Money>> = mapOf(
            TaxBasis.StateTaxable to mapOf("stateTaxable" to Money(stateTaxableCents)),
        )

        fun computeStateTaxFor(stateCode: String): Long {
            val stateTaxContext = taxContextFor(stateCode)

            val period = PayPeriod(
                id = "STATE-GOLDEN-$stateCode",
                employerId = employerId,
                dateRange = LocalDateRange(asOfDate, asOfDate),
                checkDate = asOfDate,
                frequency = PayFrequency.ANNUAL,
            )

            val snapshot = EmployeeSnapshot(
                employerId = employerId,
                employeeId = EmployeeId("EE-$stateCode-GOLDEN"),
                homeState = stateCode,
                workState = stateCode,
                filingStatus = FilingStatus.SINGLE,
                baseCompensation = BaseCompensation.Salaried(
                    annualSalary = Money(stateTaxableCents),
                    frequency = PayFrequency.ANNUAL,
                ),
            )

            val input = PaycheckInput(
                paycheckId = PaycheckId("CHK-$stateCode-GOLDEN"),
                payRunId = PayRunId("RUN-STATE-GOLDEN"),
                employerId = employerId,
                employeeId = snapshot.employeeId,
                period = period,
                employeeSnapshot = snapshot,
                timeSlice = TimeSlice(
                    period = period,
                    regularHours = 0.0,
                    overtimeHours = 0.0,
                ),
                taxContext = stateTaxContext,
                priorYtd = YtdSnapshot(year = asOfDate.year),
            )

            val result = TaxesCalculator.computeTaxes(input, bases, basisComponents)

            val stateLine = result.employeeTaxes
                .firstOrNull { it.jurisdiction.type == TaxJurisdictionType.STATE }

            return stateLine?.amount?.amount ?: 0L
        }

        val caStateTax = computeStateTaxFor("CA")
        val txStateTax = computeStateTaxFor("TX")
        val nyStateTax = computeStateTaxFor("NY")

        // Expected:
        // - CA: 5,000 * 1% = 50.00 => 5,000 cents.
        // - TX: 0% flat => 0.
        // - NY: 5,000 * 4% = 200.00 => 20,000 cents.
        assertEquals(5_000L, caStateTax, "Expected CA state tax of $50.00 on $5,000")
        assertEquals(0L, txStateTax, "Expected TX state tax of $0.00 on $5,000")
        assertEquals(20_000L, nyStateTax, "Expected NY state tax of $200.00 on $5,000")

        // Also assert ordering: NY > CA > TX.
        assertTrue(nyStateTax > caStateTax && caStateTax > txStateTax)
    }
}
