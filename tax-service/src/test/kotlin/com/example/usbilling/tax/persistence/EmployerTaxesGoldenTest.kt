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
 * Golden H2-backed tests for employer-side taxes (FUTA, SUI/SDI examples).
 */
class EmployerTaxesGoldenTest {

    private fun createDslContext(dbName: String): DSLContext = H2TaxTestSupport.createDslContext(dbName)

    private fun importConfig(dsl: DSLContext, resourcePath: String) {
        H2TaxTestSupport.importConfigFromResource(dsl, resourcePath, javaClass.classLoader)
    }

    private fun employerTaxContext(employerId: UtilityId, asOfDate: LocalDate, dsl: DSLContext): TaxContext {
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

        val employerId = UtilityId("EMP-FUTA-GOLDEN")
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
            employeeId = CustomerId("EE-FUTA-GOLDEN"),
            homeState = "CA",
            workState = "CA",
            filingStatus = FilingStatus.SINGLE,
            baseCompensation = BaseCompensation.Salaried(
                annualSalary = Money(futaWagesCents),
                frequency = PayFrequency.ANNUAL,
            ),
        )

        val input = PaycheckInput(
            paycheckId = BillId("CHK-FUTA-GOLDEN"),
            payRunId = BillRunId("RUN-FUTA-GOLDEN"),
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

        val employerId = UtilityId("EMP-SUI-GOLDEN")
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
            employeeId = CustomerId("EE-SUI-GOLDEN"),
            homeState = "CA",
            workState = "CA",
            filingStatus = FilingStatus.SINGLE,
            baseCompensation = BaseCompensation.Salaried(
                annualSalary = Money(grossWagesCents),
                frequency = PayFrequency.ANNUAL,
            ),
        )

        val input = PaycheckInput(
            paycheckId = BillId("CHK-SUI-GOLDEN"),
            payRunId = BillRunId("RUN-SUI-GOLDEN"),
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

    @Test
    fun `NY and TX SUI employer taxes applied on Gross wages from DB-backed catalog`() {
        val dsl = createDslContext("taxdb-golden-sui-multi")
        importConfig(dsl, "tax-config/example-federal-2025.json")

        val employerId = UtilityId("EMP-SUI-GOLDEN-MULTI")
        val asOfDate = LocalDate.of(2025, 3, 31)

        val taxContext = employerTaxContext(employerId, asOfDate, dsl)

        val nyRule = taxContext.employerSpecific.firstOrNull { it.id == "US_NY_SUI_2025" }
            ?: error("Expected US_NY_SUI_2025 employer tax rule in catalog")
        assertTrue(nyRule is TaxRule.FlatRateTax && nyRule.basis is TaxBasis.Gross)

        val txRule = taxContext.employerSpecific.firstOrNull { it.id == "US_TX_SUI_2025" }
            ?: error("Expected US_TX_SUI_2025 employer tax rule in catalog")
        assertTrue(txRule is TaxRule.FlatRateTax && txRule.basis is TaxBasis.Gross)

        val grossWagesCents = 10_000_00L // $10,000.00

        val period = PayPeriod(
            id = "SUI-GOLDEN-MULTI-ANNUAL",
            employerId = employerId,
            dateRange = LocalDateRange(asOfDate, asOfDate),
            checkDate = asOfDate,
            frequency = PayFrequency.ANNUAL,
        )

        fun computeEmployerTax(ruleId: String, rate: Double, wageBaseCents: Long): Long {
            val bases: Map<TaxBasis, Money> = mapOf(
                TaxBasis.Gross to Money(grossWagesCents),
            )
            val basisComponents: Map<TaxBasis, Map<String, Money>> = mapOf(
                TaxBasis.Gross to mapOf("gross" to Money(grossWagesCents)),
            )

            val snapshot = EmployeeSnapshot(
                employerId = employerId,
                employeeId = CustomerId("EE-$ruleId"),
                homeState = "CA",
                workState = "CA",
                filingStatus = FilingStatus.SINGLE,
                baseCompensation = BaseCompensation.Salaried(
                    annualSalary = Money(grossWagesCents),
                    frequency = PayFrequency.ANNUAL,
                ),
            )

            val input = PaycheckInput(
                paycheckId = BillId("CHK-$ruleId"),
                payRunId = BillRunId("RUN-SUI-GOLDEN-MULTI"),
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

            val line = result.employerTaxes.firstOrNull { it.ruleId == ruleId }
                ?: error("Expected employer tax line for $ruleId")

            return line.amount.amount
        }

        // Wage base caps: NY 8,500, TX 9,000.
        val nyTax = computeEmployerTax("US_NY_SUI_2025", 0.035, 850_000L)
        val txTax = computeEmployerTax("US_TX_SUI_2025", 0.02, 900_000L)

        // Expected amounts:
        // - NY: min(10,000, 8,500) * 3.5% = 8,500 * 0.035 = 297.50 => 29,750 cents.
        // - TX: min(10,000, 9,000) * 2.0% = 9,000 * 0.02 = 180.00 => 18,000 cents.
        assertEquals(29_750L, nyTax, "Expected NY SUI employer tax of $297.50 on $10,000 wages with $8,500 base at 3.5%")
        assertEquals(18_000L, txTax, "Expected TX SUI employer tax of $180.00 on $10,000 wages with $9,000 base at 2.0%")
    }

    @Test
    fun `additional multi-state SUI and SDI employer taxes applied on Gross wages from DB-backed catalog`() {
        val dsl = createDslContext("taxdb-golden-sui-expanded")
        importConfig(dsl, "tax-config/example-federal-2025.json")

        val employerId = UtilityId("EMP-SUI-GOLDEN-EXPANDED")
        val asOfDate = LocalDate.of(2025, 3, 31)

        val taxContext = employerTaxContext(employerId, asOfDate, dsl)

        val expectedIds = listOf(
            "US_WA_SUI_2025",
            "US_NJ_SUI_2025",
            "US_OR_SUI_2025",
            "US_MA_SUI_2025",
            "US_NJ_SDI_2025",
            "US_NY_SDI_2025",
        )

        expectedIds.forEach { id ->
            val rule = taxContext.employerSpecific.firstOrNull { it.id == id }
                ?: error("Expected $id employer tax rule in catalog")
            assertTrue(rule is TaxRule.FlatRateTax && rule.basis is TaxBasis.Gross)
        }

        val grossWagesCents = 10_000_00L // $10,000.00

        fun compute(ruleId: String): Long {
            val bases: Map<TaxBasis, Money> = mapOf(
                TaxBasis.Gross to Money(grossWagesCents),
            )
            val basisComponents: Map<TaxBasis, Map<String, Money>> = mapOf(
                TaxBasis.Gross to mapOf("gross" to Money(grossWagesCents)),
            )

            val period = PayPeriod(
                id = "SUI-GOLDEN-EXPANDED-$ruleId",
                employerId = employerId,
                dateRange = LocalDateRange(asOfDate, asOfDate),
                checkDate = asOfDate,
                frequency = PayFrequency.ANNUAL,
            )

            val snapshot = EmployeeSnapshot(
                employerId = employerId,
                employeeId = CustomerId("EE-$ruleId"),
                homeState = "CA",
                workState = "CA",
                filingStatus = FilingStatus.SINGLE,
                baseCompensation = BaseCompensation.Salaried(
                    annualSalary = Money(grossWagesCents),
                    frequency = PayFrequency.ANNUAL,
                ),
            )

            val input = PaycheckInput(
                paycheckId = BillId("CHK-$ruleId"),
                payRunId = BillRunId("RUN-SUI-GOLDEN-EXPANDED"),
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

            val line = result.employerTaxes.firstOrNull { it.ruleId == ruleId }
                ?: error("Expected employer tax line for $ruleId")

            return line.amount.amount
        }

        // Expected amounts using min(gross, wageBase) * rate.
        // WA: min(10,000, 6,200) * 1.8% = 6,200 * 0.018 = 111.60 => 11,160 cents.
        // NJ SUI: min(10,000, 4,200) * 2.75% = 4,200 * 0.0275 = 115.50 => 11,550 cents.
        // OR SUI: min(10,000, 4,500) * 2.1% = 4,500 * 0.021 = 94.50 => 9,450 cents.
        // MA SUI: min(10,000, 15,000) * 2.2% = 10,000 * 0.022 = 220.00 => 22,000 cents.
        // NJ SDI: min(10,000, 16,000) * 0.5% = 10,000 * 0.005 = 50.00 => 5,000 cents.
        // NY SDI: min(10,000, 6,500) * 0.5% = 6,500 * 0.005 = 32.50 => 3,250 cents.
        val waTax = compute("US_WA_SUI_2025")
        val njSuiTax = compute("US_NJ_SUI_2025")
        val orTax = compute("US_OR_SUI_2025")
        val maTax = compute("US_MA_SUI_2025")
        val njSdiTax = compute("US_NJ_SDI_2025")
        val nySdiTax = compute("US_NY_SDI_2025")

        assertEquals(11_160L, waTax)
        assertEquals(11_550L, njSuiTax)
        assertEquals(9_450L, orTax)
        assertEquals(22_000L, maTax)
        assertEquals(5_000L, njSdiTax)
        assertEquals(3_250L, nySdiTax)
    }
}
