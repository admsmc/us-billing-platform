package com.example.usbilling.tax.persistence

import com.example.usbilling.payroll.engine.TaxesCalculator
import com.example.usbilling.payroll.model.*
import com.example.usbilling.shared.CustomerId
import com.example.usbilling.shared.UtilityId
import com.example.usbilling.shared.Money
import com.example.usbilling.shared.BillingCycleId
import com.example.usbilling.shared.BillId
import com.example.usbilling.tax.api.TaxQuery
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
 * Golden H2-backed tests for local income taxes defined in local-income-2025.json.
 *
 * These scenarios exercise a mix of flat and bracketed local rules (NYC,
 * Philadelphia, Detroit, Columbus, Portland Metro SHS, Multnomah PFA) and go
 * end-to-end through:
 *
 *   TaxRuleFile JSON -> TaxRuleConfigImporter -> tax_rule (H2) ->
 *   H2TaxRuleRepository -> DbTaxCatalog -> TaxesCalculator
 */
class LocalIncomeGoldenTest {

    private fun createDslContext(dbName: String): DSLContext = H2TaxTestSupport.createDslContext(dbName)

    private fun importConfig(dsl: DSLContext, resourcePath: String) {
        H2TaxTestSupport.importConfigFromResource(dsl, resourcePath, javaClass.classLoader)
    }

    private fun taxContext(employerId: UtilityId, asOfDate: LocalDate, residentState: String?, workState: String?, localJurisdictions: List<String>, dsl: DSLContext): TaxContext {
        val repository: TaxRuleRepository = H2TaxRuleRepository(dsl)
        val dbCatalog = DbTaxCatalog(repository)
        val cachingCatalog = CachingTaxCatalog(dbCatalog)
        val provider = CatalogBackedTaxContextProvider(cachingCatalog)

        // For locality scenarios we construct a TaxQuery explicitly so that we
        // can vary resident/work state and local jurisdiction codes.
        val query = TaxQuery(
            employerId = employerId,
            asOfDate = asOfDate,
            residentState = residentState,
            workState = workState,
            localJurisdictions = localJurisdictions,
        )
        val rules = dbCatalog.loadRules(query)

        val federal = rules.filter { it.jurisdiction.type == TaxJurisdictionType.FEDERAL }
        val state = rules.filter { it.jurisdiction.type == TaxJurisdictionType.STATE }
        val local = rules.filter { it.jurisdiction.type == TaxJurisdictionType.LOCAL }
        val employerSpecific = rules.filter { it.jurisdiction.type == TaxJurisdictionType.OTHER }

        return TaxContext(
            federal = federal,
            state = state,
            local = local,
            employerSpecific = employerSpecific,
        )
    }

    private fun computeLocalTax(context: TaxContext, employerId: UtilityId, asOfDate: LocalDate, residentState: String, wagesCents: Long): Map<String, Long> {
        val bases: Map<TaxBasis, Money> = mapOf(
            TaxBasis.StateTaxable to Money(wagesCents),
        )
        val basisComponents: Map<TaxBasis, Map<String, Money>> = mapOf(
            TaxBasis.StateTaxable to mapOf("stateTaxable" to Money(wagesCents)),
        )

        val period = PayPeriod(
            id = "LOCAL-INCOME-ANNUAL-$employerId-$residentState-$wagesCents",
            employerId = employerId,
            dateRange = LocalDateRange(asOfDate, asOfDate),
            checkDate = asOfDate,
            frequency = PayFrequency.ANNUAL,
        )

        val snapshot = EmployeeSnapshot(
            employerId = employerId,
            employeeId = CustomerId("EE-LOCAL-INCOME-$residentState-$wagesCents"),
            homeState = residentState,
            workState = residentState,
            filingStatus = FilingStatus.SINGLE,
            baseCompensation = BaseCompensation.Salaried(
                annualSalary = Money(wagesCents),
                frequency = PayFrequency.ANNUAL,
            ),
        )

        val input = PaycheckInput(
            paycheckId = BillId("CHK-LOCAL-INCOME-$residentState-$wagesCents"),
            payRunId = BillingCycleId("RUN-LOCAL-INCOME"),
            employerId = employerId,
            employeeId = snapshot.employeeId,
            period = period,
            employeeSnapshot = snapshot,
            timeSlice = TimeSlice(
                period = period,
                regularHours = 0.0,
                overtimeHours = 0.0,
            ),
            taxContext = context,
            priorYtd = YtdSnapshot(year = asOfDate.year),
        )

        val result = TaxesCalculator.computeTaxes(input, bases, basisComponents)

        return result.employeeTaxes
            .filter { it.jurisdiction.type == TaxJurisdictionType.LOCAL }
            .associate { it.jurisdiction.code to it.amount.amount }
    }

    @Test
    fun `flat locals from local-income-2025 compute expected amounts`() {
        val dsl = createDslContext("taxdb-local-income-flat")
        importConfig(dsl, "tax-config/local-income-2025.json")

        val employerId = UtilityId("EMP-LOCAL-FLAT")
        val asOfDate = LocalDate.of(2025, 7, 31)
        val wagesCents = 100_000_00L // $100,000 state taxable wages

        fun localTax(residentState: String, localityFilter: String, expectedCode: String, dbNameSuffix: String): Long {
            val context = taxContext(
                employerId = employerId,
                asOfDate = asOfDate,
                residentState = residentState,
                workState = residentState,
                localJurisdictions = listOf(localityFilter),
                dsl = dsl,
            )

            val locals = computeLocalTax(
                context = context,
                employerId = employerId,
                asOfDate = asOfDate,
                residentState = residentState,
                wagesCents = wagesCents,
            )

            assertTrue(
                locals.containsKey(expectedCode),
                "Expected local jurisdiction $expectedCode for $residentState/$localityFilter in $dbNameSuffix",
            )

            return locals[expectedCode] ?: 0L
        }

        // Philadelphia resident wage tax: 3.74% of 100,000 => 3,740.00 => 374,000 cents.
        val phillyTax = localTax(
            residentState = "PA",
            localityFilter = "PHILADELPHIA",
            expectedCode = "PHL_WAGE",
            dbNameSuffix = "PHL",
        )
        assertEquals(374_000L, phillyTax)

        // Detroit local income tax: 2.5% of 100,000 => 2,500.00 => 250,000 cents.
        val detroitTax = localTax(
            residentState = "MI",
            localityFilter = "DETROIT",
            expectedCode = "MI_DETROIT",
            dbNameSuffix = "DETROIT",
        )
        assertEquals(250_000L, detroitTax)

        // Columbus, OH local income tax: 2.5% of 100,000 => 250,000 cents.
        val columbusTax = localTax(
            residentState = "OH",
            localityFilter = "COLUMBUS",
            expectedCode = "OH_COLUMBUS",
            dbNameSuffix = "COLUMBUS",
        )
        assertEquals(250_000L, columbusTax)
    }

    @Test
    fun `bracketed locals from local-income-2025 compute expected amounts`() {
        val dsl = createDslContext("taxdb-local-income-bracketed")
        importConfig(dsl, "tax-config/local-income-2025.json")

        val employerId = UtilityId("EMP-LOCAL-BRACKETED")
        val asOfDate = LocalDate.of(2025, 7, 31)

        fun localTax(wagesCents: Long, residentState: String, localityFilter: String, expectedCode: String): Long {
            val context = taxContext(
                employerId = employerId,
                asOfDate = asOfDate,
                residentState = residentState,
                workState = residentState,
                localJurisdictions = listOf(localityFilter),
                dsl = dsl,
            )

            val locals = computeLocalTax(
                context = context,
                employerId = employerId,
                asOfDate = asOfDate,
                residentState = residentState,
                wagesCents = wagesCents,
            )

            return locals[expectedCode] ?: 0L
        }

        // NYC bracketed local income tax: for wages fully within the first
        // bracket, tax is simply wages * 2.45%.
        val nycWages = 1_000_000L // $10,000 state taxable
        val nycTax = localTax(
            wagesCents = nycWages,
            residentState = "NY",
            localityFilter = "NYC",
            expectedCode = "NYC",
        )
        // 2.45% of 10,000 => 245.00 => 24,500 cents.
        assertEquals(24_500L, nycTax)

        // Portland Metro SHS: 0% up to 125,000, then 1% above. At 200,000 of
        // state taxable, tax is (200,000 - 125,000) * 1% = 750.00 => 75,000
        // cents.
        val shsWages = 20_000_000L // $200,000
        val shsTax = localTax(
            wagesCents = shsWages,
            residentState = "OR",
            localityFilter = "PORTLAND_METRO_SHS",
            expectedCode = "OR_METRO_SHS",
        )
        assertEquals(75_000L, shsTax)

        // Multnomah PFA: 0% up to 125,000; 1.5% on 125k-250k; 3% above 250k.
        // At 300,000 of state taxable, tax is:
        //   (250,000 - 125,000) * 1.5% + (300,000 - 250,000) * 3%
        //   = 125,000 * 1.5% + 50,000 * 3%
        //   = 1,875.00 + 1,500.00 = 3,375.00 => 337,500 cents.
        val pfaWages = 30_000_000L // $300,000
        val pfaTax = localTax(
            wagesCents = pfaWages,
            residentState = "OR",
            localityFilter = "MULTNOMAH_PFA",
            expectedCode = "OR_MULTNOMAH_PFA",
        )
        assertEquals(337_500L, pfaTax)
    }
}
