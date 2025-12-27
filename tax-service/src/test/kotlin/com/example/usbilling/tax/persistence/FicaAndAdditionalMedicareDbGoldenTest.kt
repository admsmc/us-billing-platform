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
 * Golden H2-backed tests for FICA Social Security, base Medicare, and
 * Additional Medicare rules loaded from example-federal-2025.json via the
 * TaxRuleConfigImporter/DbTaxCatalog path.
 */
class FicaAndAdditionalMedicareDbGoldenTest {

    private fun createDslContext(dbName: String): DSLContext = H2TaxTestSupport.createDslContext(dbName)

    private fun importConfig(dsl: DSLContext) {
        H2TaxTestSupport.importConfigFromResource(
            dsl,
            "tax-config/example-federal-2025.json",
            javaClass.classLoader,
        )
    }

    @Test
    fun `FICA and Medicare rules are present in DB-backed federal catalog`() {
        val dsl = createDslContext("taxdb-fica-structure")
        importConfig(dsl)

        val repository: TaxRuleRepository = H2TaxRuleRepository(dsl)
        val dbCatalog = DbTaxCatalog(repository)
        val cachingCatalog = CachingTaxCatalog(dbCatalog)
        val provider = CatalogBackedTaxContextProvider(cachingCatalog)

        val employerId = EmployerId("EMP-FICA-STRUCT")
        val asOfDate = LocalDate.of(2025, 6, 30)

        val taxContext = provider.getTaxContext(employerId, asOfDate)

        val federalIds = taxContext.federal.map { it.id }.toSet()
        assertTrue("US_FICA_SS_2025" in federalIds)
        assertTrue("US_FICA_MED_EMP_2025" in federalIds)
        assertTrue("US_FED_ADDITIONAL_MEDICARE_2025" in federalIds)

        val ssRule = taxContext.federal.first { it.id == "US_FICA_SS_2025" } as TaxRule.FlatRateTax
        val medRule = taxContext.federal.first { it.id == "US_FICA_MED_EMP_2025" } as TaxRule.FlatRateTax
        val addlRule = taxContext.federal.first { it.id == "US_FED_ADDITIONAL_MEDICARE_2025" } as TaxRule.BracketedIncomeTax

        assertEquals(TaxBasis.SocialSecurityWages, ssRule.basis)
        assertEquals(TaxBasis.MedicareWages, medRule.basis)
        assertEquals(TaxBasis.MedicareWages, addlRule.basis)

        // From example-federal-2025.json.
        assertEquals(0.062, ssRule.rate.value)
        assertEquals(0.0145, medRule.rate.value)
        assertEquals(2, addlRule.brackets.size)
    }

    @Test
    fun `FICA Social Security and Medicare taxes from DB match expected amounts`() {
        val dsl = createDslContext("taxdb-fica-amounts")
        importConfig(dsl)

        val repository: TaxRuleRepository = H2TaxRuleRepository(dsl)
        val dbCatalog = DbTaxCatalog(repository)
        val cachingCatalog = CachingTaxCatalog(dbCatalog)
        val provider = CatalogBackedTaxContextProvider(cachingCatalog)

        val employerId = EmployerId("EMP-FICA-AMOUNTS")
        val asOfDate = LocalDate.of(2025, 6, 30)

        val taxContext = provider.getTaxContext(employerId, asOfDate)

        val wagesCents = 100_000_00L // $100,000.00

        val bases: Map<TaxBasis, Money> = mapOf(
            TaxBasis.SocialSecurityWages to Money(wagesCents),
            TaxBasis.MedicareWages to Money(wagesCents),
        )
        val basisComponents: Map<TaxBasis, Map<String, Money>> = mapOf(
            TaxBasis.SocialSecurityWages to mapOf("socialSecurityWages" to Money(wagesCents)),
            TaxBasis.MedicareWages to mapOf("medicareWages" to Money(wagesCents)),
        )

        val period = PayPeriod(
            id = "FICA-ANNUAL-DB",
            employerId = employerId,
            dateRange = LocalDateRange(asOfDate, asOfDate),
            checkDate = asOfDate,
            frequency = PayFrequency.ANNUAL,
        )

        val snapshot = EmployeeSnapshot(
            employerId = employerId,
            employeeId = EmployeeId("EE-FICA-DB"),
            homeState = "CA",
            workState = "CA",
            filingStatus = FilingStatus.SINGLE,
            baseCompensation = BaseCompensation.Salaried(
                annualSalary = Money(wagesCents),
                frequency = PayFrequency.ANNUAL,
            ),
        )

        val input = PaycheckInput(
            paycheckId = PaycheckId("CHK-FICA-DB"),
            payRunId = PayRunId("RUN-FICA-DB"),
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

        val ssLine = result.employeeTaxes.firstOrNull { it.ruleId == "US_FICA_SS_2025" }
            ?: error("Expected US_FICA_SS_2025 tax line")
        val medLine = result.employeeTaxes.firstOrNull { it.ruleId == "US_FICA_MED_EMP_2025" }
            ?: error("Expected US_FICA_MED_EMP_2025 tax line")

        // From example-federal-2025.json: 6.2% SS up to 200,000; 1.45% Medicare uncapped.
        assertEquals((wagesCents * 0.062).toLong(), ssLine.amount.amount)
        assertEquals((wagesCents * 0.0145).toLong(), medLine.amount.amount)
    }

    @Test
    fun `Additional Medicare from DB applies only to wages above threshold`() {
        val dsl = createDslContext("taxdb-addl-medicare")
        importConfig(dsl)

        val repository: TaxRuleRepository = H2TaxRuleRepository(dsl)
        val dbCatalog = DbTaxCatalog(repository)
        val cachingCatalog = CachingTaxCatalog(dbCatalog)
        val provider = CatalogBackedTaxContextProvider(cachingCatalog)

        val employerId = EmployerId("EMP-ADDL-MED-DB")
        val asOfDate = LocalDate.of(2025, 6, 30)

        val taxContext = provider.getTaxContext(employerId, asOfDate)

        val current = 50_000_00L
        val prior = 190_000_00L

        val bases: Map<TaxBasis, Money> = mapOf(
            TaxBasis.MedicareWages to Money(current),
        )
        val basisComponents: Map<TaxBasis, Map<String, Money>> = mapOf(
            TaxBasis.MedicareWages to mapOf("medicareWages" to Money(current)),
        )

        val period = PayPeriod(
            id = "ADDL-MED-DB",
            employerId = employerId,
            dateRange = LocalDateRange(asOfDate, asOfDate),
            checkDate = asOfDate,
            frequency = PayFrequency.BIWEEKLY,
        )

        val snapshot = EmployeeSnapshot(
            employerId = employerId,
            employeeId = EmployeeId("EE-ADDL-MED-DB"),
            homeState = "CA",
            workState = "CA",
            filingStatus = FilingStatus.SINGLE,
            baseCompensation = BaseCompensation.Salaried(
                annualSalary = Money(100_000_00L),
                frequency = period.frequency,
            ),
        )

        val priorYtd = YtdSnapshot(
            year = asOfDate.year,
            wagesByBasis = mapOf(
                TaxBasis.MedicareWages to Money(prior),
            ),
        )

        val input = PaycheckInput(
            paycheckId = PaycheckId("CHK-ADDL-MED-DB"),
            payRunId = PayRunId("RUN-ADDL-MED-DB"),
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
            priorYtd = priorYtd,
        )

        val result = TaxesCalculator.computeTaxes(input, bases, basisComponents)

        val addlLine = result.employeeTaxes.firstOrNull { it.ruleId == "US_FED_ADDITIONAL_MEDICARE_2025" }
            ?: error("Expected US_FED_ADDITIONAL_MEDICARE_2025 tax line")

        val overThreshold = (prior + current - 200_000_00L).coerceAtLeast(0L)
        val expectedAddl = (overThreshold * 0.009).toLong()

        assertEquals(expectedAddl, addlLine.amount.amount)
    }
}
