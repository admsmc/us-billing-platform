package com.example.usbilling.tax.service

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
import kotlin.test.assertTrue

/**
 * H2-backed integration tests for [DefaultFederalWithholdingCalculator] that go
 * end-to-end from JSON config -> H2 tax_rule table -> DbTaxCatalog ->
 * CatalogBackedTaxContextProvider -> withholding calculator, using the real
 * example-federal-2025.json configuration.
 */
class FederalWithholdingIntegrationTest {

    private fun createDslContext(dbName: String): DSLContext = H2TaxTestSupport.createDslContext(dbName)

    private fun importFederalConfig(dsl: DSLContext) {
        H2TaxTestSupport.importConfigFromResource(
            dsl,
            "tax-config/example-federal-2025.json",
            javaClass.classLoader,
        )
    }

    private fun importPub15TWageBracketConfig(dsl: DSLContext) {
        H2TaxTestSupport.importConfigFromResource(
            dsl,
            "tax-config/federal-2025-pub15t-wage-bracket-biweekly.json",
            javaClass.classLoader,
        )
    }

    private fun createProvider(dsl: DSLContext): CatalogBackedTaxContextProvider {
        val repository: TaxRuleRepository = H2TaxRuleRepository(dsl)
        val dbCatalog = DbTaxCatalog(repository)
        val cachingCatalog = CachingTaxCatalog(dbCatalog)
        return CatalogBackedTaxContextProvider(cachingCatalog)
    }

    @Test
    fun `federal FIT rules with filing status are loaded from DB-backed catalog`() {
        val dsl = createDslContext("taxdb-fed-w4-int-rules")
        importFederalConfig(dsl)

        val provider = createProvider(dsl)
        val employerId = UtilityId("EMP-FED-W4-RULES")
        val asOfDate = LocalDate.of(2025, 6, 30)

        val taxContext = provider.getTaxContext(employerId, asOfDate)

        val fitRules = taxContext.federal
            .filterIsInstance<TaxRule.BracketedIncomeTax>()
            .filter { rule ->
                rule.jurisdiction.type == TaxJurisdictionType.FEDERAL &&
                    rule.jurisdiction.code == "US" &&
                    rule.basis == TaxBasis.FederalTaxable
            }

        assertTrue(
            fitRules.any { it.id == "US_FED_FIT_2025_SINGLE" && it.filingStatus == FilingStatus.SINGLE },
            "Expected SINGLE FIT rule with filingStatus SINGLE from DB-backed catalog",
        )
        assertTrue(
            fitRules.any { it.id == "US_FED_FIT_2025_MARRIED" && it.filingStatus == FilingStatus.MARRIED },
            "Expected MARRIED FIT rule with filingStatus MARRIED from DB-backed catalog",
        )
        assertTrue(
            fitRules.any { it.id == "US_FED_FIT_2025_HEAD_OF_HOUSEHOLD" && it.filingStatus == FilingStatus.HEAD_OF_HOUSEHOLD },
            "Expected HOH FIT rule with filingStatus HEAD_OF_HOUSEHOLD from DB-backed catalog",
        )
    }

    @Test
    fun `federal withholding uses filing-status-specific FIT rules from DB catalog`() {
        val dsl = createDslContext("taxdb-fed-w4-int-calc")
        importFederalConfig(dsl)

        val provider = createProvider(dsl)
        val employerId = UtilityId("EMP-FED-W4-CALC")
        val asOfDate = LocalDate.of(2025, 6, 30)

        val taxContext = provider.getTaxContext(employerId, asOfDate)

        val period = PayPeriod(
            id = "FED-W4-ANNUAL",
            employerId = employerId,
            dateRange = LocalDateRange(asOfDate, asOfDate),
            checkDate = asOfDate,
            frequency = PayFrequency.ANNUAL,
        )

        fun paycheckForStatus(status: FilingStatus): PaycheckInput {
            val wagesCents = 60_000_00L // $60,000 annual wages

            val snapshot = EmployeeSnapshot(
                employerId = employerId,
                employeeId = CustomerId("EE-$status-W4"),
                homeState = "CA",
                workState = "CA",
                filingStatus = status,
                baseCompensation = BaseCompensation.Salaried(
                    annualSalary = Money(wagesCents),
                    frequency = PayFrequency.ANNUAL,
                ),
            )

            return PaycheckInput(
                paycheckId = BillId("CHK-$status-W4"),
                payRunId = BillRunId("RUN-FED-W4"),
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
        }

        val calc = DefaultFederalWithholdingCalculator()

        val singleInput = FederalWithholdingInput(paycheckForStatus(FilingStatus.SINGLE))
        val marriedInput = FederalWithholdingInput(paycheckForStatus(FilingStatus.MARRIED))

        val singleWithholding = calc.computeWithholding(singleInput)
        val marriedWithholding = calc.computeWithholding(marriedInput)

        // With the 2025 example config, MARRIED brackets and standard deduction
        // are more favorable than SINGLE, so at the same wage level we expect
        // lower withholding for MARRIED when the calculator correctly selects
        // the filing-status-specific rule from the catalog.
        assertTrue(
            marriedWithholding.amount < singleWithholding.amount,
            "Expected married withholding to be lower than single for the same wages using DB-backed FIT rules",
        )
    }

    @Test
    fun `DB-backed federal withholding responds directionally to W-4 inputs`() {
        val dsl = createDslContext("taxdb-fed-w4-int-behavior")
        importFederalConfig(dsl)

        val provider = createProvider(dsl)
        val employerId = UtilityId("EMP-FED-W4-BEHAVIOR")
        val asOfDate = LocalDate.of(2025, 6, 30)

        val taxContext = provider.getTaxContext(employerId, asOfDate)

        val period = PayPeriod(
            id = "FED-W4-BEHAVIOR-ANNUAL",
            employerId = employerId,
            dateRange = LocalDateRange(asOfDate, asOfDate),
            checkDate = asOfDate,
            frequency = PayFrequency.ANNUAL,
        )

        fun paycheckWithW4(w4CreditCents: Long? = null, w4OtherIncomeCents: Long? = null, w4DeductionsCents: Long? = null): FederalWithholdingInput {
            val wagesCents = 50_000_00L // $50,000 annual wages

            val snapshot = EmployeeSnapshot(
                employerId = employerId,
                employeeId = CustomerId("EE-W4-BEHAVIOR"),
                homeState = "CA",
                workState = "CA",
                filingStatus = FilingStatus.SINGLE,
                baseCompensation = BaseCompensation.Salaried(
                    annualSalary = Money(wagesCents),
                    frequency = PayFrequency.ANNUAL,
                ),
                w4AnnualCreditAmount = w4CreditCents?.let { Money(it) },
                w4OtherIncomeAnnual = w4OtherIncomeCents?.let { Money(it) },
                w4DeductionsAnnual = w4DeductionsCents?.let { Money(it) },
            )

            val input = PaycheckInput(
                paycheckId = BillId("CHK-W4-BEHAVIOR"),
                payRunId = BillRunId("RUN-FED-W4-BEHAVIOR"),
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

            return FederalWithholdingInput(input)
        }

        val calc = DefaultFederalWithholdingCalculator()

        val baseline = calc.computeWithholding(paycheckWithW4())
        val withCredit = calc.computeWithholding(
            paycheckWithW4(w4CreditCents = 2_000_00L),
        )
        val withOtherIncome = calc.computeWithholding(
            paycheckWithW4(w4OtherIncomeCents = 5_000_00L),
        )
        val withDeductions = calc.computeWithholding(
            paycheckWithW4(w4DeductionsCents = 5_000_00L),
        )

        // Directional assertions only: we are not locking in specific dollar
        // amounts, just the monotonic behavior of the W-4 inputs.
        assertTrue(
            withCredit.amount < baseline.amount,
            "W-4 credits should reduce DB-backed federal withholding relative to baseline",
        )
        assertTrue(
            withOtherIncome.amount > baseline.amount,
            "W-4 other income should increase DB-backed federal withholding relative to baseline",
        )
        assertTrue(
            withDeductions.amount < baseline.amount,
            "W-4 deductions should decrease DB-backed federal withholding relative to baseline",
        )
    }

    @Test
    fun `nonresident alien has higher withholding than resident for same biweekly wages`() {
        val dsl = createDslContext("taxdb-fed-w4-int-nra")
        importFederalConfig(dsl)

        val provider = createProvider(dsl)
        val employerId = UtilityId("EMP-FED-W4-NRA")
        val asOfDate = LocalDate.of(2025, 6, 30)

        val taxContext = provider.getTaxContext(employerId, asOfDate)

        val period = PayPeriod(
            id = "FED-W4-NRA-BIWEEKLY",
            employerId = employerId,
            dateRange = LocalDateRange(asOfDate.minusDays(13), asOfDate),
            checkDate = asOfDate,
            frequency = PayFrequency.BIWEEKLY,
        )

        fun paycheck(isNra: Boolean): FederalWithholdingInput {
            val wagesCents = 52_000_00L // $52,000 annual -> $2,000 biweekly

            val snapshot = EmployeeSnapshot(
                employerId = employerId,
                employeeId = CustomerId("EE-NRA-$isNra"),
                homeState = "CA",
                workState = "CA",
                filingStatus = FilingStatus.SINGLE,
                baseCompensation = BaseCompensation.Salaried(
                    annualSalary = Money(wagesCents),
                    frequency = period.frequency,
                ),
                isNonresidentAlien = isNra,
            )

            val input = PaycheckInput(
                paycheckId = BillId("CHK-NRA-$isNra"),
                payRunId = BillRunId("RUN-FED-W4-NRA"),
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

            return FederalWithholdingInput(input)
        }

        val calc = DefaultFederalWithholdingCalculator()

        val resident = calc.computeWithholding(paycheck(isNra = false))
        val nra = calc.computeWithholding(paycheck(isNra = true))

        assertTrue(
            nra.amount > resident.amount,
            "Expected nonresident alien withholding to exceed resident withholding for the same biweekly wages using DB-backed FIT rules",
        )
    }

    @Test
    fun `nonresident alien has higher withholding than resident for same weekly wages`() {
        val dsl = createDslContext("taxdb-fed-w4-int-nra-weekly")
        importFederalConfig(dsl)

        val provider = createProvider(dsl)
        val employerId = UtilityId("EMP-FED-W4-NRA-WEEKLY")
        val asOfDate = LocalDate.of(2025, 6, 30)

        val taxContext = provider.getTaxContext(employerId, asOfDate)

        val period = PayPeriod(
            id = "FED-W4-NRA-WEEKLY",
            employerId = employerId,
            dateRange = LocalDateRange(asOfDate.minusDays(6), asOfDate),
            checkDate = asOfDate,
            frequency = PayFrequency.WEEKLY,
        )

        fun paycheck(isNra: Boolean): FederalWithholdingInput {
            val wagesCents = 52_000_00L // $52,000 annual -> ~$1,000 weekly

            val snapshot = EmployeeSnapshot(
                employerId = employerId,
                employeeId = CustomerId("EE-NRA-WEEKLY-$isNra"),
                homeState = "CA",
                workState = "CA",
                filingStatus = FilingStatus.SINGLE,
                baseCompensation = BaseCompensation.Salaried(
                    annualSalary = Money(wagesCents),
                    frequency = period.frequency,
                ),
                isNonresidentAlien = isNra,
            )

            val input = PaycheckInput(
                paycheckId = BillId("CHK-NRA-WEEKLY-$isNra"),
                payRunId = BillRunId("RUN-FED-W4-NRA-WEEKLY"),
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

            return FederalWithholdingInput(input)
        }

        val calc = DefaultFederalWithholdingCalculator()

        val resident = calc.computeWithholding(paycheck(isNra = false))
        val nra = calc.computeWithholding(paycheck(isNra = true))

        assertTrue(
            nra.amount > resident.amount,
            "Expected nonresident alien withholding to exceed resident withholding for the same weekly wages using DB-backed FIT rules",
        )
    }

    @Test
    fun `Step 2 multiple jobs biweekly wage-bracket yields higher FIT than standard`() {
        val dsl = H2TaxTestSupport.createDslContext("taxdb-fed-w4-step2-wb")
        H2TaxTestSupport.importConfigFromResource(
            dsl,
            "tax-config/federal-2025-pub15t-wage-bracket-biweekly.json",
            javaClass.classLoader,
        )

        val provider = createProvider(dsl)
        val employerId = UtilityId("EMP-FED-W4-STEP2-WB")
        val asOfDate = LocalDate.of(2025, 6, 30)

        val taxContext = provider.getTaxContext(employerId, asOfDate)

        val period = PayPeriod(
            id = "FED-W4-STEP2-WB-BI",
            employerId = employerId,
            dateRange = LocalDateRange(asOfDate.minusDays(13), asOfDate),
            checkDate = asOfDate,
            frequency = PayFrequency.BIWEEKLY,
        )

        fun paycheck(step2: Boolean): FederalWithholdingInput {
            val wagesCents = 1_000_00L // $1,000 biweekly -> in first or second bracket

            val snapshot = EmployeeSnapshot(
                employerId = employerId,
                employeeId = CustomerId("EE-STEP2-WB-$step2"),
                homeState = "CA",
                workState = "CA",
                filingStatus = FilingStatus.SINGLE,
                baseCompensation = BaseCompensation.Salaried(
                    annualSalary = Money(wagesCents * 26),
                    frequency = period.frequency,
                ),
                w4Step2MultipleJobs = step2,
            )

            val input = PaycheckInput(
                paycheckId = BillId("CHK-STEP2-WB-$step2"),
                payRunId = BillRunId("RUN-FED-W4-STEP2-WB"),
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

            return FederalWithholdingInput(input)
        }

        val calc = DefaultFederalWithholdingCalculator(method = "WAGE_BRACKET")

        val standard = calc.computeWithholding(paycheck(step2 = false))
        val withStep2 = calc.computeWithholding(paycheck(step2 = true))

        assertTrue(
            withStep2.amount > standard.amount,
            "Expected wage-bracket Step 2 multiple-jobs withholding to exceed STANDARD withholding for the same biweekly wages",
        )
    }

    @Test
    fun `percentage and wage-bracket methods are approximately consistent for biweekly SINGLE`() {
        val dsl = createDslContext("taxdb-fed-w4-cross-method-single")
        importFederalConfig(dsl)
        importPub15TWageBracketConfig(dsl)

        val provider = createProvider(dsl)
        val employerId = UtilityId("EMP-FED-W4-CROSS-METHOD-SINGLE")
        val asOfDate = LocalDate.of(2025, 6, 30)

        val taxContext = provider.getTaxContext(employerId, asOfDate)

        val period = PayPeriod(
            id = "FED-W4-CROSS-METHOD-BI",
            employerId = employerId,
            dateRange = LocalDateRange(asOfDate.minusDays(13), asOfDate),
            checkDate = asOfDate,
            frequency = PayFrequency.BIWEEKLY,
        )

        fun paycheck(wagesCents: Long): FederalWithholdingInput {
            val snapshot = EmployeeSnapshot(
                employerId = employerId,
                employeeId = CustomerId("EE-CROSS-METHOD-$wagesCents"),
                homeState = "CA",
                workState = "CA",
                filingStatus = FilingStatus.SINGLE,
                baseCompensation = BaseCompensation.Salaried(
                    annualSalary = Money(wagesCents * 26),
                    frequency = period.frequency,
                ),
            )

            val input = PaycheckInput(
                paycheckId = BillId("CHK-CROSS-METHOD-$wagesCents"),
                payRunId = BillRunId("RUN-FED-W4-CROSS-METHOD"),
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

            return FederalWithholdingInput(input)
        }

        val calcPercentage = DefaultFederalWithholdingCalculator(method = "PERCENTAGE")
        val calcWageBracket = DefaultFederalWithholdingCalculator(method = "WAGE_BRACKET")

        val wages = 2_000_00L // $2,000 biweekly (~$52,000 annual)
        val pct = calcPercentage.computeWithholding(paycheck(wages))
        val wb = calcWageBracket.computeWithholding(paycheck(wages))

        // Compute absolute difference in cents without relying on kotlin.math.abs
        // to avoid overload ambiguity.
        val diffCents = if (pct.amount >= wb.amount) {
            pct.amount - wb.amount
        } else {
            wb.amount - pct.amount
        }
        // Allow a modest tolerance per period; IRS percentage and table methods
        // are designed to be numerically close but are not guaranteed identical.
        val toleranceCents = 5_00L // $5.00
        assertTrue(
            diffCents <= toleranceCents,
            "Expected percentage and wage-bracket methods to be within $5 per period for biweekly SINGLE (diff=$diffCents)",
        )
    }
}
