package com.example.usbilling.worker

import com.example.usbilling.hr.HrApplication
import com.example.usbilling.payroll.model.*
import com.example.usbilling.persistence.flyway.FlywaySupport
import com.example.usbilling.shared.EmployeeId
import com.example.usbilling.shared.EmployerId
import com.example.usbilling.tax.api.TaxCatalog
import com.example.usbilling.tax.api.TaxQuery
import com.example.usbilling.tax.config.TaxRuleFile
import com.example.usbilling.tax.impl.CachingTaxCatalog
import com.example.usbilling.tax.impl.DbTaxCatalog
import com.example.usbilling.tax.impl.TaxRuleRecord
import com.example.usbilling.tax.impl.TaxRuleRepository
import com.example.usbilling.tax.persistence.TaxRuleConfigImporter
import com.example.usbilling.worker.support.StubLaborClientTestConfig
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.h2.jdbcx.JdbcDataSource
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestInstance.Lifecycle
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.util.TestPropertyValues
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.context.ContextConfiguration
import java.nio.charset.StandardCharsets
import java.sql.Connection
import java.time.LocalDate
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ContextConfiguration(initializers = [MichiganLocalityHrTaxIntegrationTest.Companion.Initializer::class])
@TestInstance(Lifecycle.PER_CLASS)
@Import(MichiganLocalityHrTaxIntegrationTest.TestTaxConfig::class, StubLaborClientTestConfig::class)
class MichiganLocalityHrTaxIntegrationTest {

    companion object {
        lateinit var hrContext: ConfigurableApplicationContext

        class Initializer : ApplicationContextInitializer<ConfigurableApplicationContext> {
            override fun initialize(context: ConfigurableApplicationContext) {
                hrContext = SpringApplicationBuilder(HrApplication::class.java)
                    .run(
                        "--server.port=0",
                        "--spring.datasource.url=jdbc:h2:mem:hr_mi_local_tax",
                        "--spring.datasource.driverClassName=org.h2.Driver",
                        "--spring.datasource.username=sa",
                        "--spring.datasource.password=",
                        "--spring.flyway.enabled=true",
                        "--spring.flyway.locations=classpath:db/migration/hr",
                    )

                val dataSource = hrContext.getBean(DataSource::class.java)

                fun Connection.exec(sql: String, vararg args: Any?) {
                    prepareStatement(sql).use { ps ->
                        args.forEachIndexed { index, arg ->
                            ps.setObject(index + 1, arg)
                        }
                        ps.executeUpdate()
                    }
                }

                val employerId = "EMP-HR-MI-LOCAL-TAX"
                val detroitEmpId = "EE-MI-DETROIT"
                val annArborEmpId = "EE-MI-ANNARBOR"
                val grandRapidsEmpId = "EE-MI-GR"
                val lansingEmpId = "EE-MI-LANSING"
                val payPeriodId = "2025-01-BW-MI-LOCALS"
                val checkDate = LocalDate.of(2025, 3, 31)

                dataSource.connection.use { conn ->
                    conn.exec("DELETE FROM employment_compensation")
                    conn.exec("DELETE FROM employee_profile_effective")
                    conn.exec("DELETE FROM employee")
                    conn.exec("DELETE FROM pay_period")

                    fun insertEmployee(employeeId: String, workCity: String) {
                        val hireDate = LocalDate.of(2024, 6, 1)

                        conn.exec(
                            """
                            INSERT INTO employee (
                              employer_id, employee_id, home_state, work_state, work_city,
                              filing_status, employment_type,
                              hire_date, termination_date,
                              dependents,
                              federal_withholding_exempt, is_nonresident_alien,
                              w4_annual_credit_cents, w4_other_income_cents, w4_deductions_cents,
                              w4_step2_multiple_jobs,
                              additional_withholding_cents,
                              fica_exempt, flsa_enterprise_covered, flsa_exempt_status, is_tipped_employee
                            ) VALUES (?, ?, 'MI', 'MI', ?, 'SINGLE', 'REGULAR', ?, NULL, 0,
                                      FALSE, FALSE,
                                      NULL, NULL, NULL,
                                      FALSE,
                                      NULL,
                                      FALSE, TRUE, 'NON_EXEMPT', FALSE)
                            """.trimIndent(),
                            employerId,
                            employeeId,
                            workCity,
                            hireDate,
                        )

                        // HR snapshots now read from employee_profile_effective.
                        conn.exec(
                            """
                            INSERT INTO employee_profile_effective (
                              employer_id, employee_id,
                              effective_from, effective_to,
                              home_state, work_state, work_city,
                              filing_status, employment_type,
                              hire_date, termination_date,
                              dependents,
                              federal_withholding_exempt, is_nonresident_alien,
                              w4_step2_multiple_jobs,
                              additional_withholding_cents,
                              fica_exempt, flsa_enterprise_covered, flsa_exempt_status, is_tipped_employee
                            ) VALUES (?, ?, ?, ?, 'MI', 'MI', ?, 'SINGLE', 'REGULAR', ?, NULL, 0,
                                      FALSE, FALSE,
                                      FALSE,
                                      NULL,
                                      FALSE, TRUE, 'NON_EXEMPT', FALSE)
                            """.trimIndent(),
                            employerId,
                            employeeId,
                            hireDate,
                            LocalDate.of(9999, 12, 31),
                            workCity,
                            hireDate,
                        )
                    }

                    insertEmployee(detroitEmpId, "Detroit")
                    insertEmployee(annArborEmpId, "Ann Arbor")
                    insertEmployee(grandRapidsEmpId, "Grand Rapids")
                    insertEmployee(lansingEmpId, "Lansing")

                    fun insertComp(employeeId: String) {
                        conn.exec(
                            """
                            INSERT INTO employment_compensation (
                              employer_id, employee_id,
                              effective_from, effective_to,
                              compensation_type,
                              annual_salary_cents, hourly_rate_cents, pay_frequency
                            ) VALUES (?, ?, ?, ?, 'SALARIED', 100_00000, NULL, 'ANNUAL')
                            """.trimIndent(),
                            employerId,
                            employeeId,
                            LocalDate.of(2024, 6, 1),
                            LocalDate.of(9999, 12, 31),
                        )
                    }

                    insertComp(detroitEmpId)
                    insertComp(annArborEmpId)
                    insertComp(grandRapidsEmpId)
                    insertComp(lansingEmpId)

                    conn.exec(
                        """
                        INSERT INTO pay_period (
                          employer_id, id,
                          start_date, end_date, check_date,
                          frequency, sequence_in_year
                        ) VALUES (?, ?, ?, ?, ?, 'ANNUAL', 1)
                        """.trimIndent(),
                        employerId,
                        payPeriodId,
                        LocalDate.of(2025, 1, 1),
                        LocalDate.of(2025, 12, 31),
                        checkDate,
                    )
                }

                val hrPort = requireNotNull(hrContext.environment.getProperty("local.server.port")) {
                    "Expected hr-service to expose local.server.port"
                }

                TestPropertyValues.of(
                    "downstreams.hr.base-url=http://localhost:$hrPort",
                    // Avoid incidental garnishment application from HR demo rules.
                    "worker.garnishments.enabled-employers=EMP-NOT-MI-LOCAL-TAX",
                ).applyTo(context.environment)
            }
        }
    }

    @org.springframework.boot.test.context.TestConfiguration
    class TestTaxConfig {

        @Bean
        @Primary
        fun h2BackedTaxClient(): com.example.usbilling.worker.client.TaxClient = H2CatalogTaxClient()
    }

    class H2CatalogTaxClient : com.example.usbilling.worker.client.TaxClient {

        private val dsl: DSLContext
        private val catalog: TaxCatalog

        init {
            dsl = createDslContext("taxdb-worker-mi-locals")
            importConfig(dsl, "tax-config/example-federal-2025.json")
            importConfig(dsl, "tax-config/state-income-2025.json")
            importConfig(dsl, "tax-config/mi-locals-2025.json")

            val repository: TaxRuleRepository = H2TaxRuleRepository(dsl)
            val dbCatalog = DbTaxCatalog(repository)
            catalog = CachingTaxCatalog(dbCatalog)
        }

        override fun getTaxContext(employerId: EmployerId, asOfDate: LocalDate, residentState: String?, workState: String?, localityCodes: List<String>): TaxContext {
            val query = TaxQuery(
                employerId = employerId,
                asOfDate = asOfDate,
                residentState = residentState ?: "MI",
                workState = workState ?: "MI",
                localJurisdictions = localityCodes,
            )

            val rules = catalog.loadRules(query)

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

        private fun createDslContext(dbName: String): DSLContext {
            val ds = JdbcDataSource().apply {
                setURL("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1;MODE=PostgreSQL")
                user = "sa"
                password = ""
            }

            // Create the tax_rule schema using the real Flyway migration.
            FlywaySupport.migrate(
                dataSource = ds,
                "classpath:db/migration/tax",
            )

            return DSL.using(ds, SQLDialect.H2)
        }

        private fun importConfig(dsl: DSLContext, resourcePath: String) {
            val importer = TaxRuleConfigImporter(dsl)
            val objectMapper = jacksonObjectMapper()
                .registerModule(JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

            val loader = javaClass.classLoader
            val stream = requireNotNull(loader.getResourceAsStream(resourcePath)) {
                "Missing test resource $resourcePath"
            }

            val json = stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            val file: TaxRuleFile = objectMapper.readValue(json)
            importer.importRules(file.rules)
        }

        class H2TaxRuleRepository(
            private val dsl: DSLContext,
        ) : TaxRuleRepository {

            override fun findRulesFor(query: TaxQuery): List<TaxRuleRecord> {
                val t = DSL.table("tax_rule")

                val conditions = mutableListOf<org.jooq.Condition>()

                conditions += org.jooq.impl.DSL.or(
                    org.jooq.impl.DSL.field("employer_id").isNull,
                    org.jooq.impl.DSL.field("employer_id").eq(query.employerId.value),
                )

                conditions += org.jooq.impl.DSL.field("effective_from", LocalDate::class.java).le(query.asOfDate)
                conditions += org.jooq.impl.DSL.field("effective_to", LocalDate::class.java).gt(query.asOfDate)

                query.residentState?.let { state ->
                    conditions += org.jooq.impl.DSL.or(
                        org.jooq.impl.DSL.field("resident_state_filter").isNull,
                        org.jooq.impl.DSL.field("resident_state_filter").eq(state),
                    )
                }

                query.workState?.let { state ->
                    conditions += org.jooq.impl.DSL.or(
                        org.jooq.impl.DSL.field("work_state_filter").isNull,
                        org.jooq.impl.DSL.field("work_state_filter").eq(state),
                    )
                }

                if (query.localJurisdictions.isEmpty()) {
                    conditions += org.jooq.impl.DSL.field("locality_filter").isNull
                } else {
                    conditions += org.jooq.impl.DSL.or(
                        org.jooq.impl.DSL.field("locality_filter").isNull,
                        org.jooq.impl.DSL.field("locality_filter").`in`(query.localJurisdictions),
                    )
                }

                val whereCondition = conditions
                    .fold<org.jooq.Condition, org.jooq.Condition?>(null) { acc, c -> acc?.and(c) ?: c }
                    ?: org.jooq.impl.DSL.noCondition()

                val records = dsl
                    .selectFrom(t)
                    .where(whereCondition)
                    .fetch()

                return records.map { r ->
                    TaxRuleRecord(
                        id = r.get("ID", String::class.java)!!,
                        jurisdictionType = TaxJurisdictionType.valueOf(
                            r.get("JURISDICTION_TYPE", String::class.java)!!,
                        ),
                        jurisdictionCode = r.get("JURISDICTION_CODE", String::class.java)!!,
                        basis = when (val raw = r.get("BASIS", String::class.java)!!) {
                            "Gross" -> TaxBasis.Gross
                            "FederalTaxable" -> TaxBasis.FederalTaxable
                            "StateTaxable" -> TaxBasis.StateTaxable
                            "SocialSecurityWages" -> TaxBasis.SocialSecurityWages
                            "MedicareWages" -> TaxBasis.MedicareWages
                            "SupplementalWages" -> TaxBasis.SupplementalWages
                            "FutaWages" -> TaxBasis.FutaWages
                            else -> error("Unknown TaxBasis '$raw' from tax_rule.basis in H2 test repository")
                        },
                        ruleType = TaxRuleRecord.RuleType.valueOf(r.get("RULE_TYPE", String::class.java)!!),
                        rate = r.get("RATE", Double::class.java),
                        annualWageCapCents = r.get("ANNUAL_WAGE_CAP_CENTS", Long::class.java),
                        bracketsJson = r.get("BRACKETS_JSON", String::class.java),
                        standardDeductionCents = r.get("STANDARD_DEDUCTION_CENTS", Long::class.java),
                        additionalWithholdingCents = r.get("ADDITIONAL_WITHHOLDING_CENTS", Long::class.java),
                        employerId = r.get("EMPLOYER_ID", String::class.java)?.let(::EmployerId),
                        effectiveFrom = r.get("EFFECTIVE_FROM", LocalDate::class.java),
                        effectiveTo = r.get("EFFECTIVE_TO", LocalDate::class.java),
                        filingStatus = r.get("FILING_STATUS", String::class.java),
                        residentStateFilter = r.get("RESIDENT_STATE_FILTER", String::class.java),
                        workStateFilter = r.get("WORK_STATE_FILTER", String::class.java),
                        localityFilter = r.get("LOCALITY_FILTER", String::class.java),
                    )
                }
            }
        }
    }

    @Autowired
    lateinit var payrollRunService: PayrollRunService

    @AfterAll
    fun tearDown() {
        hrContext.close()
    }

    @Test
    fun `Detroit employee has MI_DETROIT local tax while non-local MI employee does not`() {
        val employerId = EmployerId("EMP-HR-MI-LOCAL-TAX")
        val payPeriodId = "2025-01-BW-MI-LOCALS"
        val detroitEmp = EmployeeId("EE-MI-DETROIT")
        val annArborEmp = EmployeeId("EE-MI-ANNARBOR")

        val results = payrollRunService.runHrBackedPayForPeriod(
            employerId = employerId,
            payPeriodId = payPeriodId,
            employeeIds = listOf(detroitEmp, annArborEmp),
        ).associateBy { it.employeeId }

        val detroitPaycheck = requireNotNull(results[detroitEmp])
        val annArborPaycheck = requireNotNull(results[annArborEmp])

        assertEquals(detroitPaycheck.gross.amount, annArborPaycheck.gross.amount)

        fun totalByType(result: PaycheckResult, type: TaxJurisdictionType): Long = result.employeeTaxes
            .filter { it.jurisdiction.type == type }
            .sumOf { it.amount.amount }

        val detroitFederal = totalByType(detroitPaycheck, TaxJurisdictionType.FEDERAL)
        val annArborFederal = totalByType(annArborPaycheck, TaxJurisdictionType.FEDERAL)
        val detroitState = totalByType(detroitPaycheck, TaxJurisdictionType.STATE)
        val annArborState = totalByType(annArborPaycheck, TaxJurisdictionType.STATE)
        val detroitLocal = totalByType(detroitPaycheck, TaxJurisdictionType.LOCAL)
        val annArborLocal = totalByType(annArborPaycheck, TaxJurisdictionType.LOCAL)

        assertEquals(detroitFederal, annArborFederal)
        assertEquals(detroitState, annArborState)

        assertTrue(detroitLocal > 0L)
        assertEquals(0L, annArborLocal)

        assertTrue(
            detroitPaycheck.employeeTaxes.any {
                it.jurisdiction.type == TaxJurisdictionType.LOCAL && it.jurisdiction.code == "MI_DETROIT"
            },
        )
        assertTrue(
            annArborPaycheck.employeeTaxes.none { it.jurisdiction.type == TaxJurisdictionType.LOCAL },
        )
    }

    @Test
    fun `Grand Rapids and Lansing employees both have local tax with GR higher than Lansing`() {
        val employerId = EmployerId("EMP-HR-MI-LOCAL-TAX")
        val payPeriodId = "2025-01-BW-MI-LOCALS"
        val grandRapidsEmp = EmployeeId("EE-MI-GR")
        val lansingEmp = EmployeeId("EE-MI-LANSING")

        val results = payrollRunService.runHrBackedPayForPeriod(
            employerId = employerId,
            payPeriodId = payPeriodId,
            employeeIds = listOf(grandRapidsEmp, lansingEmp),
        ).associateBy { it.employeeId }

        val grPaycheck = requireNotNull(results[grandRapidsEmp])
        val lansingPaycheck = requireNotNull(results[lansingEmp])

        assertEquals(grPaycheck.gross.amount, lansingPaycheck.gross.amount)

        fun localTotal(result: PaycheckResult): Long = result.employeeTaxes
            .filter { it.jurisdiction.type == TaxJurisdictionType.LOCAL }
            .sumOf { it.amount.amount }

        val grLocal = localTotal(grPaycheck)
        val lansingLocal = localTotal(lansingPaycheck)

        assertTrue(grLocal > 0L)
        assertTrue(lansingLocal > 0L)
        assertTrue(grLocal > lansingLocal)

        assertTrue(
            grPaycheck.employeeTaxes.any {
                it.jurisdiction.type == TaxJurisdictionType.LOCAL && it.jurisdiction.code == "MI_GRAND_RAPIDS"
            },
        )
        assertTrue(
            lansingPaycheck.employeeTaxes.any {
                it.jurisdiction.type == TaxJurisdictionType.LOCAL && it.jurisdiction.code == "MI_LANSING"
            },
        )
    }
}
