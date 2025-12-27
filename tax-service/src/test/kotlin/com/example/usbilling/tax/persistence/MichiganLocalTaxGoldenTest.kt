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
 * Golden H2-backed tests for Michigan local income taxes (Detroit, Grand Rapids,
 * Lansing) driven by locality_filter and TaxQuery.localJurisdictions.
 */
class MichiganLocalTaxGoldenTest {

    private fun createDslContext(dbName: String): DSLContext = H2TaxTestSupport.createDslContext(dbName)

    private fun importConfig(dsl: DSLContext, resourcePath: String) {
        H2TaxTestSupport.importConfigFromResource(dsl, resourcePath, javaClass.classLoader)
    }

    private fun taxContext(employerId: EmployerId, asOfDate: LocalDate, residentState: String?, localJurisdictions: List<String>, dsl: DSLContext): TaxContext {
        val repository: TaxRuleRepository = H2TaxRuleRepository(dsl)
        val dbCatalog = DbTaxCatalog(repository)
        val cachingCatalog = CachingTaxCatalog(dbCatalog)
        val provider = CatalogBackedTaxContextProvider(cachingCatalog)

        // TaxQuery is created inside provider; we only vary residentState via
        // EmployeeSnapshot and localJurisdictions via the query. For H2 tests,
        // we simulate this by temporarily constructing a TaxQuery-equivalent
        // path: residentState is baked into tax rules via resident_state_filter,
        // and locality_filter is matched against localJurisdictions.
        // The provider itself currently only populates employerId/asOfDate,
        // so for now we pass localityJurisdictions via a custom catalog layer.

        // In this test we bypass CatalogBackedTaxContextProvider's internal
        // TaxQuery construction and instead directly call the repository via
        // a synthetic TaxQuery.
        val query = com.example.usbilling.tax.api.TaxQuery(
            employerId = employerId,
            asOfDate = asOfDate,
            residentState = residentState,
            workState = residentState,
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

    private fun computeLocalTax(context: TaxContext, employerId: EmployerId, asOfDate: LocalDate, residentState: String, localJurisdictions: List<String>): Map<String, Long> {
        val wagesCents = 100_000_00L // $100,000 state taxable wages
        val bases: Map<TaxBasis, Money> = mapOf(
            TaxBasis.StateTaxable to Money(wagesCents),
        )
        val basisComponents: Map<TaxBasis, Map<String, Money>> = mapOf(
            TaxBasis.StateTaxable to mapOf("stateTaxable" to Money(wagesCents)),
        )

        val period = PayPeriod(
            id = "MI-LOCAL-ANNUAL-$employerId-${localJurisdictions.joinToString("-")}",
            employerId = employerId,
            dateRange = LocalDateRange(asOfDate, asOfDate),
            checkDate = asOfDate,
            frequency = PayFrequency.ANNUAL,
        )

        val snapshot = EmployeeSnapshot(
            employerId = employerId,
            employeeId = EmployeeId("EE-MI-LOCAL"),
            homeState = residentState,
            workState = residentState,
            filingStatus = FilingStatus.SINGLE,
            baseCompensation = BaseCompensation.Salaried(
                annualSalary = Money(wagesCents),
                frequency = PayFrequency.ANNUAL,
            ),
        )

        val input = PaycheckInput(
            paycheckId = PaycheckId("CHK-MI-LOCAL-$employerId-${localJurisdictions.joinToString("-")}"),
            payRunId = PayRunId("RUN-MI-LOCAL"),
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
    fun `Detroit local tax applies only when DETROIT locality is present`() {
        val dsl = createDslContext("taxdb-mi-locals-detroit")
        importConfig(dsl, "tax-config/mi-locals-2025.json")

        val employerId = EmployerId("EMP-MI-LOCAL")
        val asOfDate = LocalDate.of(2025, 3, 31)
        val residentState = "MI"

        // With DETROIT locality.
        val contextWithDetroit = taxContext(
            employerId = employerId,
            asOfDate = asOfDate,
            residentState = residentState,
            localJurisdictions = listOf("DETROIT"),
            dsl = dsl,
        )

        assertTrue(contextWithDetroit.local.any { it.jurisdiction.code == "MI_DETROIT" })

        val localWithDetroit = computeLocalTax(
            context = contextWithDetroit,
            employerId = employerId,
            asOfDate = asOfDate,
            residentState = residentState,
            localJurisdictions = listOf("DETROIT"),
        )

        // 2.5% of 100,000 = 2,500.00 => 250,000 cents.
        assertEquals(250_000L, localWithDetroit["MI_DETROIT"])

        // Without DETROIT locality: simulate a Michigan resident working in a
        // different city that has no configured local tax rule.
        val contextWithoutDetroit = taxContext(
            employerId = employerId,
            asOfDate = asOfDate,
            residentState = residentState,
            localJurisdictions = listOf("OTHER_MI_CITY"),
            dsl = dsl,
        )

        val localWithoutDetroit = computeLocalTax(
            context = contextWithoutDetroit,
            employerId = employerId,
            asOfDate = asOfDate,
            residentState = residentState,
            localJurisdictions = listOf("OTHER_MI_CITY"),
        )

        // No Detroit local tax when locality does not include DETROIT.
        assertEquals(null, localWithoutDetroit["MI_DETROIT"])
    }

    @Test
    fun `Grand Rapids and Lansing locals both apply but at different rates`() {
        val dsl = createDslContext("taxdb-mi-locals-gr-lansing")
        importConfig(dsl, "tax-config/mi-locals-2025.json")

        val employerId = EmployerId("EMP-MI-LOCAL-2")
        val asOfDate = LocalDate.of(2025, 3, 31)
        val residentState = "MI"

        fun localTaxFor(cityCode: String, localityFilter: String): Long {
            val context = taxContext(
                employerId = employerId,
                asOfDate = asOfDate,
                residentState = residentState,
                localJurisdictions = listOf(localityFilter),
                dsl = dsl,
            )

            val locals = computeLocalTax(
                context = context,
                employerId = employerId,
                asOfDate = asOfDate,
                residentState = residentState,
                localJurisdictions = listOf(localityFilter),
            )

            return locals[cityCode] ?: 0L
        }

        val grTax = localTaxFor("MI_GRAND_RAPIDS", "GRAND_RAPIDS")
        val lansingTax = localTaxFor("MI_LANSING", "LANSING")

        // 2.0% of 100,000 = 2,000.00 => 200,000 cents.
        assertEquals(200_000L, grTax)
        // 1.8% of 100,000 = 1,800.00 => 180,000 cents.
        assertEquals(180_000L, lansingTax)
    }
}
