package com.example.uspayroll.tax.persistence

import com.example.uspayroll.payroll.model.*
import com.example.uspayroll.payroll.engine.TaxesCalculator
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
    fun `federal FIT bracket structure for 2025 per filing status`() {
        val dsl = createDslContext("taxdb-golden-fit-structure")

        importConfig(dsl, "tax-config/example-federal-2025.json")

        val repository: TaxRuleRepository = H2TaxRuleRepository(dsl)
        val dbCatalog = DbTaxCatalog(repository)
        val cachingCatalog = CachingTaxCatalog(dbCatalog)
        val provider = CatalogBackedTaxContextProvider(cachingCatalog)

        val employerId = EmployerId("EMP-FED-FIT-STRUCTURE")
        val asOfDate = LocalDate.of(2025, 6, 30)

        val taxContext = provider.getTaxContext(employerId, asOfDate)

        fun findFitRule(id: String): TaxRule.BracketedIncomeTax = taxContext.federal
            .filterIsInstance<TaxRule.BracketedIncomeTax>()
            .firstOrNull { it.id == id }
            ?: error("Expected $id in federal rules")

        val single = findFitRule("US_FED_FIT_2025_SINGLE")
        val married = findFitRule("US_FED_FIT_2025_MARRIED")
        val hoh = findFitRule("US_FED_FIT_2025_HEAD_OF_HOUSEHOLD")

        // Each status should now have 4 brackets with the top bracket at 24%.
        assertEquals(4, single.brackets.size, "SINGLE should have 4 brackets")
        assertEquals(4, married.brackets.size, "MARRIED should have 4 brackets")
        assertEquals(4, hoh.brackets.size, "HOH should have 4 brackets")

        assertEquals(0.24, single.brackets.last().rate.value, 1e-9, "Top SINGLE rate should be 24%")
        assertEquals(0.24, married.brackets.last().rate.value, 1e-9, "Top MARRIED rate should be 24%")
        assertEquals(0.24, hoh.brackets.last().rate.value, 1e-9, "Top HOH rate should be 24%")
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
    fun `federal FIT scenarios for SINGLE, MARRIED, HOH at multiple incomes`() {
        val dsl = createDslContext("taxdb-golden-fed-multi")

        importConfig(dsl, "tax-config/example-federal-2025.json")

        val repository: TaxRuleRepository = H2TaxRuleRepository(dsl)
        val dbCatalog = DbTaxCatalog(repository)
        val cachingCatalog = CachingTaxCatalog(dbCatalog)
        val provider = CatalogBackedTaxContextProvider(cachingCatalog)

        val employerId = EmployerId("EMP-FED-GOLDEN-MULTI")
        val asOfDate = LocalDate.of(2025, 6, 30)

        val taxContext = provider.getTaxContext(employerId, asOfDate)

        fun computeFit(
            filingStatus: FilingStatus,
            wagesCents: Long,
        ): Long {
            val bases: Map<TaxBasis, Money> = mapOf(
                TaxBasis.FederalTaxable to Money(wagesCents),
            )
            val basisComponents: Map<TaxBasis, Map<String, Money>> = mapOf(
                TaxBasis.FederalTaxable to mapOf("federalTaxable" to Money(wagesCents)),
            )

            val period = PayPeriod(
                id = "FED-GOLDEN-${filingStatus.name}-$wagesCents",
                employerId = employerId,
                dateRange = LocalDateRange(asOfDate, asOfDate),
                checkDate = asOfDate,
                frequency = PayFrequency.ANNUAL,
            )

            val snapshot = EmployeeSnapshot(
                employerId = employerId,
                employeeId = EmployeeId("EE-FED-${filingStatus.name}-$wagesCents"),
                homeState = "CA",
                workState = "CA",
                filingStatus = filingStatus,
                baseCompensation = BaseCompensation.Salaried(
                    annualSalary = Money(wagesCents),
                    frequency = PayFrequency.ANNUAL,
                ),
            )

            val input = PaycheckInput(
                paycheckId = PaycheckId("CHK-FED-${filingStatus.name}-$wagesCents"),
                payRunId = PayRunId("RUN-FED-GOLDEN-MULTI"),
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

            val prefix = when (filingStatus) {
                FilingStatus.SINGLE -> "US_FED_FIT_2025_SINGLE"
                FilingStatus.MARRIED -> "US_FED_FIT_2025_MARRIED"
                FilingStatus.HEAD_OF_HOUSEHOLD -> "US_FED_FIT_2025_HEAD_OF_HOUSEHOLD"
            }

            val fitLine = result.employeeTaxes.firstOrNull { it.ruleId == prefix }
                ?: error("Expected $prefix tax line in employee taxes")

            return fitLine.amount.amount
        }

        val single30k = computeFit(FilingStatus.SINGLE, 3_000_000L)
        val single50k = computeFit(FilingStatus.SINGLE, 5_000_000L)
        val single100k = computeFit(FilingStatus.SINGLE, 10_000_000L)

        val married50k = computeFit(FilingStatus.MARRIED, 5_000_000L)
        val married100k = computeFit(FilingStatus.MARRIED, 10_000_000L)

        val hoh50k = computeFit(FilingStatus.HEAD_OF_HOUSEHOLD, 5_000_000L)

        // Monotonicity and relative checks (not pinning exact cents beyond the
        // existing 50k SINGLE golden scenario).
        assertTrue(single30k < single50k, "SINGLE 30k should owe less FIT than SINGLE 50k")
        assertTrue(single50k < single100k, "SINGLE 50k should owe less FIT than SINGLE 100k")

        // At the same wage level, MARRIED should have less or equal FIT than SINGLE.
        assertTrue(married50k <= single50k, "MARRIED 50k should not owe more FIT than SINGLE 50k")
        assertTrue(married100k <= single100k, "MARRIED 100k should not owe more FIT than SINGLE 100k")

        // HOH should typically be more favorable than SINGLE at the same income.
        assertTrue(hoh50k <= single50k, "HOH 50k should not owe more FIT than SINGLE 50k")
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

    @Test
    fun `state income regression across multiple incomes for CA and NY`() {
        val dsl = createDslContext("taxdb-golden-state-multi")

        importConfig(dsl, "tax-config/example-federal-2025.json")
        importConfig(dsl, "tax-config/state-income-2025.json")

        val repository: TaxRuleRepository = H2TaxRuleRepository(dsl)
        val dbCatalog = DbTaxCatalog(repository)
        val cachingCatalog = CachingTaxCatalog(dbCatalog)
        val provider = CatalogBackedTaxContextProvider(cachingCatalog)

        val employerId = EmployerId("EMP-STATE-GOLDEN-MULTI")
        val asOfDate = LocalDate.of(2025, 1, 15)

        val baseTaxContext = provider.getTaxContext(employerId, asOfDate)

        fun taxContextFor(stateCode: String): TaxContext = baseTaxContext.copy(
            state = baseTaxContext.state.filter {
                it.jurisdiction.type == TaxJurisdictionType.STATE && it.jurisdiction.code == stateCode
            },
        )

        fun computeStateTax(
            stateCode: String,
            taxableCents: Long,
        ): Long {
            val stateTaxContext = taxContextFor(stateCode)

            val bases: Map<TaxBasis, Money> = mapOf(
                TaxBasis.StateTaxable to Money(taxableCents),
            )
            val basisComponents: Map<TaxBasis, Map<String, Money>> = mapOf(
                TaxBasis.StateTaxable to mapOf("stateTaxable" to Money(taxableCents)),
            )

            val period = PayPeriod(
                id = "STATE-GOLDEN-MULTI-$stateCode-$taxableCents",
                employerId = employerId,
                dateRange = LocalDateRange(asOfDate, asOfDate),
                checkDate = asOfDate,
                frequency = PayFrequency.ANNUAL,
            )

            val snapshot = EmployeeSnapshot(
                employerId = employerId,
                employeeId = EmployeeId("EE-$stateCode-GOLDEN-MULTI-$taxableCents"),
                homeState = stateCode,
                workState = stateCode,
                filingStatus = FilingStatus.SINGLE,
                baseCompensation = BaseCompensation.Salaried(
                    annualSalary = Money(taxableCents),
                    frequency = PayFrequency.ANNUAL,
                ),
            )

            val input = PaycheckInput(
                paycheckId = PaycheckId("CHK-$stateCode-GOLDEN-MULTI-$taxableCents"),
                payRunId = PayRunId("RUN-STATE-GOLDEN-MULTI"),
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

        val incomes = listOf(5_000_00L, 20_000_00L, 50_000_00L)

        val (caLow, caMid, caHigh) = incomes.map { computeStateTax("CA", it) }
        val (nyLow, nyMid, nyHigh) = incomes.map { computeStateTax("NY", it) }

        assertTrue(caLow < caMid && caMid < caHigh, "CA state tax should increase with income")
        assertTrue(nyLow < nyMid && nyMid < nyHigh, "NY state tax should increase with income")

        // At each level, NY rate is higher than CA in our synthetic config; ensure NY tax > CA tax.
        assertTrue(nyLow > caLow)
        assertTrue(nyMid > caMid)
        assertTrue(nyHigh > caHigh)
    }

    @Test
    fun `additional state income golden scenario AL vs AZ`() {
        val dsl = createDslContext("taxdb-golden-state-al-az")

        // Import both federal and state rules; we'll focus on state lines.
        importConfig(dsl, "tax-config/example-federal-2025.json")
        importConfig(dsl, "tax-config/state-income-2025.json")

        val repository: TaxRuleRepository = H2TaxRuleRepository(dsl)
        val dbCatalog = DbTaxCatalog(repository)
        val cachingCatalog = CachingTaxCatalog(dbCatalog)
        val provider = CatalogBackedTaxContextProvider(cachingCatalog)

        val employerId = EmployerId("EMP-STATE-GOLDEN-AL-AZ")
        val asOfDate = LocalDate.of(2025, 1, 15)

        val baseTaxContext = provider.getTaxContext(employerId, asOfDate)

        fun taxContextFor(stateCode: String): TaxContext = baseTaxContext.copy(
            state = baseTaxContext.state.filter {
                it.jurisdiction.type == TaxJurisdictionType.STATE && it.jurisdiction.code == stateCode
            },
        )

        // Use a simple state taxable base that lies in the first bracket for AL
        // and is taxed at the flat rate for AZ.
        val stateTaxableCents = 40_000L // $400.00

        fun computeStateTaxFor(stateCode: String): Long {
            val stateTaxContext = taxContextFor(stateCode)

            val bases: Map<TaxBasis, Money> = mapOf(
                TaxBasis.StateTaxable to Money(stateTaxableCents),
            )
            val basisComponents: Map<TaxBasis, Map<String, Money>> = mapOf(
                TaxBasis.StateTaxable to mapOf("stateTaxable" to Money(stateTaxableCents)),
            )

            val period = PayPeriod(
                id = "STATE-GOLDEN-$stateCode-AL-AZ",
                employerId = employerId,
                dateRange = LocalDateRange(asOfDate, asOfDate),
                checkDate = asOfDate,
                frequency = PayFrequency.ANNUAL,
            )

            val snapshot = EmployeeSnapshot(
                employerId = employerId,
                employeeId = EmployeeId("EE-$stateCode-AL-AZ"),
                homeState = stateCode,
                workState = stateCode,
                filingStatus = FilingStatus.SINGLE,
                baseCompensation = BaseCompensation.Salaried(
                    annualSalary = Money(stateTaxableCents),
                    frequency = PayFrequency.ANNUAL,
                ),
            )

            val input = PaycheckInput(
                paycheckId = PaycheckId("CHK-$stateCode-AL-AZ"),
                payRunId = PayRunId("RUN-STATE-GOLDEN-AL-AZ"),
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

        val alTax = computeStateTaxFor("AL")
        val azTax = computeStateTaxFor("AZ")

        // Expected:
        // - AL: first bracket up to $500 at 2% -> 400 * 2% = 8.00 => 800 cents.
        // - AZ: flat 2.5% -> 400 * 2.5% = 10.00 => 1,000 cents.
        assertEquals(800L, alTax, "Expected AL state tax of $8.00 on $400 at 2%")
        assertEquals(1_000L, azTax, "Expected AZ state tax of $10.00 on $400 at 2.5%")
    }

    @Test
    fun `NYC local tax applied only for NYC locality`() {
        val dsl = createDslContext("taxdb-golden-local-nyc")

        // Import example federal (which includes a synthetic NYC local rule) and
        // state income rules. We'll focus on the local component.
        importConfig(dsl, "tax-config/example-federal-2025.json")
        importConfig(dsl, "tax-config/state-income-2025.json")

        val repository: TaxRuleRepository = H2TaxRuleRepository(dsl)
        val dbCatalog = DbTaxCatalog(repository)


        val employerId = EmployerId("EMP-LOCAL-NYC")
        val asOfDate = LocalDate.of(2025, 1, 15)

        val stateTaxableCents = 500_000L // $5,000.00
        val bases: Map<TaxBasis, Money> = mapOf(
            TaxBasis.StateTaxable to Money(stateTaxableCents),
        )
        val basisComponents: Map<TaxBasis, Map<String, Money>> = mapOf(
            TaxBasis.StateTaxable to mapOf("stateTaxable" to Money(stateTaxableCents)),
        )

        fun loadTaxContext(
            residentState: String,
            workState: String,
            localJurisdictions: List<String>,
        ): TaxContext {
            val query = TaxQuery(
                employerId = employerId,
                asOfDate = asOfDate,
                residentState = residentState,
                workState = workState,
                localJurisdictions = localJurisdictions,
            )

            val rules = dbCatalog.loadRules(query)
            return TaxContext(
                federal = rules.filter { it.jurisdiction.type == TaxJurisdictionType.FEDERAL },
                state = rules.filter { it.jurisdiction.type == TaxJurisdictionType.STATE },
                local = rules.filter { it.jurisdiction.type == TaxJurisdictionType.LOCAL },
                employerSpecific = rules.filter { it.jurisdiction.type == TaxJurisdictionType.OTHER },
            )
        }

        fun computeLocalTax(
            employeeIdSuffix: String,
            residentState: String,
            workState: String,
            localJurisdictions: List<String>,
        ): Long {
            val period = PayPeriod(
                id = "LOCAL-NYC-$employeeIdSuffix",
                employerId = employerId,
                dateRange = LocalDateRange(asOfDate, asOfDate),
                checkDate = asOfDate,
                frequency = PayFrequency.ANNUAL,
            )

            val snapshot = EmployeeSnapshot(
                employerId = employerId,
                employeeId = EmployeeId("EE-LOCAL-NYC-$employeeIdSuffix"),
                homeState = residentState,
                workState = workState,
                filingStatus = FilingStatus.SINGLE,
                baseCompensation = BaseCompensation.Salaried(
                    annualSalary = Money(stateTaxableCents),
                    frequency = PayFrequency.ANNUAL,
                ),
            )

            val taxContext = loadTaxContext(
                residentState = residentState,
                workState = workState,
                localJurisdictions = localJurisdictions,
            )

            val input = PaycheckInput(
                paycheckId = PaycheckId("CHK-LOCAL-NYC-$employeeIdSuffix"),
                payRunId = PayRunId("RUN-LOCAL-NYC"),
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

            val localLine = result.employeeTaxes
                .firstOrNull { it.jurisdiction.type == TaxJurisdictionType.LOCAL }

            return localLine?.amount?.amount ?: 0L
        }

        val nycResidentLocal = computeLocalTax(
            employeeIdSuffix = "NYC-RES",
            residentState = "NY",
            workState = "NY",
            localJurisdictions = listOf("NYC"),
        )

        val nonNyResidentLocal = computeLocalTax(
            employeeIdSuffix = "NON-NY",
            residentState = "NJ",
            workState = "NJ",
            localJurisdictions = listOf("NOT_NYC"),
        )

        // At 3.5% of $5,000, NYC local tax should be $175.00 => 17,500 cents
        // when the locality is requested via TaxQuery, and zero otherwise.
        assertEquals(17_500L, nycResidentLocal, "Expected NYC local tax of $175.00 on $5,000 when querying for NYC")
        assertEquals(0L, nonNyResidentLocal, "Expected no NYC local tax when querying for a non-NYC locality")
    }
}
