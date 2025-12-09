package com.example.uspayroll.tax.persistence

import com.example.uspayroll.payroll.engine.PayrollEngine
import com.example.uspayroll.payroll.model.*
import com.example.uspayroll.payroll.model.TaxRule.BracketedIncomeTax
import com.example.uspayroll.payroll.model.TaxRule.FlatRateTax
import com.example.uspayroll.shared.EmployeeId
import com.example.uspayroll.shared.EmployerId
import com.example.uspayroll.shared.Money
import com.example.uspayroll.shared.PaycheckId
import com.example.uspayroll.shared.PayRunId
import com.example.uspayroll.tax.api.TaxQuery
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
import java.nio.charset.StandardCharsets
import java.time.LocalDate

class DbTaxCatalogIntegrationTest {

    private fun createDslContext(): DSLContext = H2TaxTestSupport.createDslContext("taxdb-int")

    private fun importConfig(dsl: DSLContext, resourcePath: String) {
        H2TaxTestSupport.importConfigFromResource(dsl, resourcePath, javaClass.classLoader)
    }

    @Test
    fun `loads federal and state rules from DB into in-memory TaxContext`() {
        val dsl = createDslContext()

        // Import example federal rules and state income tax rules into the in-memory DB.
        importConfig(dsl, "tax-config/example-federal-2025.json")
        importConfig(dsl, "tax-config/state-income-2025.json")

        // Wire a simple H2-backed repository, catalog, caching layer, and TaxContextProvider.
        val repository: TaxRuleRepository = H2TaxRuleRepository(dsl)
        val dbCatalog = DbTaxCatalog(repository)
        val cachingCatalog = CachingTaxCatalog(dbCatalog)
        val provider = CatalogBackedTaxContextProvider(cachingCatalog)

        val employerId = EmployerId("EMP_DB")
        val asOfDate = LocalDate.of(2025, 6, 30)

        val taxContext = provider.getTaxContext(employerId, asOfDate)

        // Federal: we expect the example federal FIT rule plus other federal rules.
        val federalIds = taxContext.federal.map(TaxRule::id).toSet()
        assertTrue(
            "US_FED_FIT_2025_SINGLE" in federalIds,
            "Expected federal FIT rule to be loaded from DB-backed catalog",
        )

        val federalFit = taxContext.federal
            .filterIsInstance<BracketedIncomeTax>()
            .first { it.id == "US_FED_FIT_2025_SINGLE" }

        // Brackets for FIT should be parsed from brackets_json into domain TaxBracket.
        assertEquals(3, federalFit.brackets.size)
        assertEquals(0.10, federalFit.brackets.first().rate.value)
        assertEquals(0.22, federalFit.brackets.last().rate.value)

        // State: we expect at least one CA state income tax rule in the context.
        val caStateRules = taxContext.state
            .filterIsInstance<BracketedIncomeTax>()
            .filter { it.jurisdiction.code == "CA" }

        assertTrue(
            caStateRules.isNotEmpty(),
            "Expected at least one CA state income tax rule loaded from DB",
        )

        val caSingle = caStateRules
            .first { it.brackets.isNotEmpty() }

        // CA SINGLE should have a reasonable number of brackets and a top rate of 12.3%.
        assertTrue(caSingle.brackets.size >= 5, "Expected multiple CA brackets from DB")
        assertEquals(0.123, caSingle.brackets.last().rate.value)

        // Ensure the catalog is not tied to the DB after load: subsequent calls use cached TaxContext.
        // We can't easily observe DB calls here without a custom repository, but we can at least
        // assert that the second call returns an identical context instance from the cache.
        val taxContext2 = provider.getTaxContext(employerId, asOfDate)
        assertEquals(taxContext, taxContext2)
    }

    @Test
    fun `DB-backed TaxContextProvider drives different state income tax for CA TX NY`() {
        val dsl = createDslContext()

        // Import example federal rules and state income tax rules into the in-memory DB.
        importConfig(dsl, "tax-config/example-federal-2025.json")
        importConfig(dsl, "tax-config/state-income-2025.json")

        val repository: TaxRuleRepository = H2TaxRuleRepository(dsl)
        val dbCatalog = DbTaxCatalog(repository)
        val cachingCatalog = CachingTaxCatalog(dbCatalog)
        val provider = CatalogBackedTaxContextProvider(cachingCatalog)

        val employerId = EmployerId("EMP-SIT-DB")
        val asOfDate = LocalDate.of(2025, 1, 15)

        // Load TaxContext once from the DB-backed provider. All subsequent work is in-memory.
        val baseTaxContext = provider.getTaxContext(employerId, asOfDate)

        fun basePeriod(): PayPeriod = PayPeriod(
            id = "2025-01-BW-SIT-DB",
            employerId = employerId,
            dateRange = LocalDateRange(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 14)),
            checkDate = asOfDate,
            frequency = PayFrequency.BIWEEKLY,
        )

        fun baseSnapshot(employeeId: String, homeState: String, workState: String): EmployeeSnapshot =
            EmployeeSnapshot(
                employerId = employerId,
                employeeId = EmployeeId(employeeId),
                homeState = homeState,
                workState = workState,
                filingStatus = FilingStatus.SINGLE,
                baseCompensation = BaseCompensation.Salaried(
                    annualSalary = Money(260_000_00L), // $260,000 annual
                    frequency = PayFrequency.BIWEEKLY,
                ),
            )

        val period = basePeriod()
        val caSnapshot = baseSnapshot("EE-CA-DB", homeState = "CA", workState = "CA")
        val txSnapshot = baseSnapshot("EE-TX-DB", homeState = "TX", workState = "TX")
        val nySnapshot = baseSnapshot("EE-NY-DB", homeState = "NY", workState = "NY")

        fun taxContextFor(stateCode: String): TaxContext = baseTaxContext.copy(
            state = baseTaxContext.state.filter { it.jurisdiction.type == TaxJurisdictionType.STATE && it.jurisdiction.code == stateCode },
        )

        fun runFor(snapshot: EmployeeSnapshot): PaycheckResult {
            val perEmployeeTaxContext = taxContextFor(snapshot.homeState)

            val input = PaycheckInput(
                paycheckId = PaycheckId("CHK-${'$'}{snapshot.employeeId.value}"),
                payRunId = PayRunId("RUN-SIT-DB"),
                employerId = employerId,
                employeeId = snapshot.employeeId,
                period = period,
                employeeSnapshot = snapshot,
                timeSlice = TimeSlice(
                    period = period,
                    regularHours = 0.0,
                    overtimeHours = 0.0,
                ),
                taxContext = perEmployeeTaxContext,
                priorYtd = YtdSnapshot(year = 2025),
            )

            return PayrollEngine.calculatePaycheck(input)
        }

        val caResult = runFor(caSnapshot)
        val txResult = runFor(txSnapshot)
        val nyResult = runFor(nySnapshot)

        // Gross should be the same for all three employees.
        assertEquals(caResult.gross.amount, txResult.gross.amount)
        assertEquals(caResult.gross.amount, nyResult.gross.amount)

        fun stateTaxCents(result: PaycheckResult): Long =
            result.employeeTaxes
                .firstOrNull { it.jurisdiction.type == TaxJurisdictionType.STATE }
                ?.amount
                ?.amount
                ?: 0L

        val caStateTax = stateTaxCents(caResult)
        val txStateTax = stateTaxCents(txResult)
        val nyStateTax = stateTaxCents(nyResult)

        // TX has 0% state income tax; CA and NY are progressive and should be positive.
        assertEquals(0L, txStateTax, "Expected TX state income tax to be zero from DB-backed rules")
        assertTrue(caStateTax > 0L, "Expected CA state income tax to be positive from DB-backed rules")
        assertTrue(nyStateTax > 0L, "Expected NY state income tax to be positive from DB-backed rules")
        assertTrue(nyStateTax > caStateTax, "Expected NY state income tax to exceed CA for this wage level from DB-backed rules")

        // Net pay ordering should reflect these differences: NY < CA < TX.
        assertTrue(nyResult.net.amount < caResult.net.amount, "NY net should be less than CA net with DB-backed rules")
        assertTrue(caResult.net.amount < txResult.net.amount, "CA net should be less than TX net with DB-backed rules")
    }
}
