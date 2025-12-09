package com.example.uspayroll.tax.persistence

import com.example.uspayroll.payroll.engine.TaxesCalculator
import com.example.uspayroll.payroll.model.*
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
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Golden H2-backed tests for employer-side taxes (FUTA, SUI/SDI examples).
 */
class EmployerTaxesGoldenTest {

    private fun createDslContext(dbName: String): DSLContext =
        H2TaxTestSupport.createDslContext(dbName)

    private fun importConfig(dsl: DSLContext, resourcePath: String) {
        H2TaxTestSupport.importConfigFromResource(dsl, resourcePath, javaClass.classLoader)
    }

    private fun employerTaxContext(employerId: EmployerId, asOfDate: LocalDate, dsl: DSLContext): TaxContext {
        val repository: TaxRuleRepository = H2TaxRuleRepository(dsl)
        val dbCatalog = DbTaxCatalog(repository)
        val cachingCatalog = CachingTaxCatalog(dbCatalog)
        val provider = CatalogBackedTaxContextProvider(cachingCatalog)
        return provider.getTaxContext(employerId, asOfDate)
    }

    @Test
    fun `FUTA employer tax applied on FutaWages from DB-backed catalog`() {
        val dsl = createDslContext("taxdb-golden-futa")
        importConfig(dsl, "tax-config/example-federal-2025.json")

        val employerId = EmployerId("EMP-FUTA-GOLDEN")
        val asOfDate = LocalDate.of(2025, 3, 31)

        val taxContext = employerTaxContext(employerId, asOfDate, dsl)

        val futaRule = taxContext.employerSpecific.firstOrNull { it.id == "US_FUTA_2025" }
            ?: error("Expected US_FUTA_2025 employer tax rule in catalog")
        assertTrue(futaRule is TaxRule.FlatRateTax && futaRule.basis is TaxBasis.FutaWages)

        // Use FUTA wages equal to the annual wage base so that all wages are
        // taxed at 0.6% in this simple example.
        val futaWagesCents = 700_000L // $7,000.00

        val bases: Map<TaxBasis, Money> = mapOf(
            TaxBasis.FutaWages to Money(futaWagesCents),
        )
        val basisComponents: Map<TaxBasis, Map<String, Money>> = mapOf(
            TaxBasis.FutaWages to mapOf("futaWages" to Money(futaWagesCents)),
        )

        val period = PayPeriod(
            id = "FUTA-GOLDEN-ANNUAL",
            employerId = employerId,
            dateRange = LocalDateRange(asOfDate, asOfDate),
            checkDate = asOfDate,
            frequency = PayFrequency.ANNUAL,
        )

        val snapshot = EmployeeSnapshot(
            employerId = employerId,
            employeeId = EmployeeId("EE-FUTA-GOLDEN"),
            homeState = "CA",
            workState = "CA",
            filingStatus = FilingStatus.SINGLE,
            baseCompensation = BaseCompensation.Salaried(
                annualSalary = Money(futaWagesCents),
                frequency = PayFrequency.ANNUAL,
            ),
        )

        val input = PaycheckInput(
            paycheckId = PaycheckId("CHK-FUTA-GOLDEN"),
            payRunId = PayRunId("RUN-FUTA-GOLDEN"),
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

        val futaLine = result.employerTaxes.firstOrNull { it.ruleId == "US_FUTA_2025" }
            ?: error("Expected FUTA employer tax line in employer taxes")

        // 0.6% of $7,000.00 = $42.00 => 4,200 cents.
        assertEquals(4_200L, futaLine.amount.amount, "Expected FUTA employer tax of $42.00 on $7,000 FUTA wages")
    }

    @Test
    fun `SUI employer tax example applied on Gross wages from DB-backed catalog`() {
        val dsl = createDslContext("taxdb-golden-sui")
        importConfig(dsl, "tax-config/example-federal-2025.json")

        val employerId = EmployerId("EMP-SUI-GOLDEN")
        val asOfDate = LocalDate.of(2025, 3, 31)

        val taxContext = employerTaxContext(employerId, asOfDate, dsl)

        val suiRule = taxContext.employerSpecific.firstOrNull { it.id == "US_CA_SUI_2025" }
            ?: error("Expected US_CA_SUI_2025 employer tax rule in catalog")
        assertTrue(suiRule is TaxRule.FlatRateTax && suiRule.basis is TaxBasis.Gross)

        // Use gross wages below the wage base so the full amount is taxed at 3%.
        val grossWagesCents = 10_000_00L // $10,000.00

        val bases: Map<TaxBasis, Money> = mapOf(
            TaxBasis.Gross to Money(grossWagesCents),
        )
        val basisComponents: Map<TaxBasis, Map<String, Money>> = mapOf(
            TaxBasis.Gross to mapOf("gross" to Money(grossWagesCents)),
        )

        val period = PayPeriod(
            id = "SUI-GOLDEN-ANNUAL",
            employerId = employerId,
            dateRange = LocalDateRange(asOfDate, asOfDate),
            checkDate = asOfDate,
            frequency = PayFrequency.ANNUAL,
        )

        val snapshot = EmployeeSnapshot(
            employerId = employerId,
            employeeId = EmployeeId("EE-SUI-GOLDEN"),
            homeState = "CA",
            workState = "CA",
            filingStatus = FilingStatus.SINGLE,
            baseCompensation = BaseCompensation.Salaried(
                annualSalary = Money(grossWagesCents),
                frequency = PayFrequency.ANNUAL,
            ),
        )

        val input = PaycheckInput(
            paycheckId = PaycheckId("CHK-SUI-GOLDEN"),
            payRunId = PayRunId("RUN-SUI-GOLDEN"),
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

        val suiLine = result.employerTaxes.firstOrNull { it.ruleId == "US_CA_SUI_2025" }
            ?: error("Expected SUI employer tax line in employer taxes")

        // Wage base is $7,000; at 3% this yields $210.00 => 21,000 cents.
        assertEquals(21_000L, suiLine.amount.amount, "Expected SUI employer tax of $210.00 given a $7,000 wage base at 3%")
    }
}