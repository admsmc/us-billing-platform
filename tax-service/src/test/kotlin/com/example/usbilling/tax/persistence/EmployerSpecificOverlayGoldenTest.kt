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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Golden H2-backed test proving employer-specific tax overlays via employer_id
 * in the tax_rule table.
 */
class EmployerSpecificOverlayGoldenTest {

    private fun createDslContext(dbName: String): DSLContext = H2TaxTestSupport.createDslContext(dbName)

    private fun importConfig(dsl: DSLContext, resourcePath: String) {
        H2TaxTestSupport.importConfigFromResource(dsl, resourcePath, javaClass.classLoader)
    }

    private fun taxContext(employerId: UtilityId, asOfDate: LocalDate, dsl: DSLContext): TaxContext {
        val repository: TaxRuleRepository = H2TaxRuleRepository(dsl)
        val dbCatalog = DbTaxCatalog(repository)
        val cachingCatalog = CachingTaxCatalog(dbCatalog)
        val provider = CatalogBackedTaxContextProvider(cachingCatalog)
        return provider.getTaxContext(employerId, asOfDate)
    }

    @Test
    fun `employer-specific CA state surcharge applied only for overlay employer`() {
        val dsl = createDslContext("taxdb-employer-overlay")
        // Load base federal + employer taxes, then employer-specific overlays.
        importConfig(dsl, "tax-config/example-federal-2025.json")
        importConfig(dsl, "tax-config/employer-overlays-2025.json")

        val asOfDate = LocalDate.of(2025, 3, 31)
        val annualWagesCents = 100_000_00L // $100,000 annual wages

        val acme = UtilityId("EMP-ACME")
        val baseline = UtilityId("EMP-BASELINE")

        val acmeContext = taxContext(acme, asOfDate, dsl)
        val baselineContext = taxContext(baseline, asOfDate, dsl)

        // Sanity-check that employer-specific overlay rules are wired as expected in TaxContext.
        val acmeStateRuleIds = acmeContext.state.map { it.id }.toSet()
        val baselineStateRuleIds = baselineContext.state.map { it.id }.toSet()

        assertTrue("US_CA_EMP_ACME_SURCHARGE_2025" in acmeStateRuleIds)
        assertFalse("US_CA_EMP_BASELINE_SURCHARGE_2025" in acmeStateRuleIds)

        assertTrue("US_CA_EMP_BASELINE_SURCHARGE_2025" in baselineStateRuleIds)
        assertFalse("US_CA_EMP_ACME_SURCHARGE_2025" in baselineStateRuleIds)

        fun computeStateTax(context: TaxContext, employerId: UtilityId, employeeId: String): Long {
            val bases: Map<TaxBasis, Money> = mapOf(
                TaxBasis.StateTaxable to Money(annualWagesCents),
            )
            val basisComponents: Map<TaxBasis, Map<String, Money>> = mapOf(
                TaxBasis.StateTaxable to mapOf("stateTaxable" to Money(annualWagesCents)),
            )

            val period = PayPeriod(
                id = "STATE-OVERLAY-ANNUAL-$employerId",
                employerId = employerId,
                dateRange = LocalDateRange(asOfDate, asOfDate),
                checkDate = asOfDate,
                frequency = PayFrequency.ANNUAL,
            )

            val snapshot = EmployeeSnapshot(
                employerId = employerId,
                employeeId = CustomerId(employeeId),
                homeState = "CA",
                workState = "CA",
                filingStatus = FilingStatus.SINGLE,
                baseCompensation = BaseCompensation.Salaried(
                    annualSalary = Money(annualWagesCents),
                    frequency = PayFrequency.ANNUAL,
                ),
            )

            val input = PaycheckInput(
                paycheckId = BillId("CHK-STATE-OVERLAY-$employerId"),
                payRunId = BillRunId("RUN-STATE-OVERLAY"),
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

            // Sum all STATE-level employee taxes as a simple proxy for CA income tax.
            return result.employeeTaxes
                .filter { line -> line.jurisdiction.type == TaxJurisdictionType.STATE && line.jurisdiction.code == "CA" }
                .sumOf { it.amount.amount }
        }

        val acmeStateTax = computeStateTax(acmeContext, acme, "EE-ACME")
        val baselineStateTax = computeStateTax(baselineContext, baseline, "EE-BASELINE")

        // Overlay is a 1% flat surcharge on $100,000 => 1,000.00 => 100,000 cents difference.
        val surchargeCents = (annualWagesCents * 0.01).toLong()

        assertEquals(
            baselineStateTax + surchargeCents,
            acmeStateTax,
            "Expected EMP-ACME to pay baseline CA state tax plus 1% employer-specific surcharge",
        )
    }
}
