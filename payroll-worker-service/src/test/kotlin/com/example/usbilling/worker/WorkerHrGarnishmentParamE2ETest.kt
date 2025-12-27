package com.example.usbilling.worker

import com.example.usbilling.hr.HrApplication
import com.example.usbilling.hr.client.HrClientProperties
import com.example.usbilling.payroll.model.TraceStep
import com.example.usbilling.payroll.model.garnishment.GarnishmentType
import com.example.usbilling.shared.EmployeeId
import com.example.usbilling.shared.EmployerId
import com.example.usbilling.worker.support.StubTaxLaborClientsTestConfig
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestInstance.Lifecycle
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Parameterized cross-module E2E test that starts hr-service and worker-service
 * in the same Spring Boot context and verifies that multiple garnishment rule
 * shapes from hr-service's JSON config are applied end-to-end.
 */
@SpringBootTest(
    classes = [WorkerApplication::class, HrApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        // Ensure HR migrations run in this combined context (other services'
        // application.yml defaults can otherwise leak onto the classpath).
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration/hr",

        // This suite validates rule-driven garnishment behavior via the HR HTTP API.
        "hr.garnishments.fallback-enabled=true",
    ],
)
@Import(StubTaxLaborClientsTestConfig::class)
@TestInstance(Lifecycle.PER_CLASS)
class WorkerHrGarnishmentParamE2ETest {

    data class SeedOrder(
        val orderId: String,
        val type: GarnishmentType,
        val issuingJurisdictionType: String,
        val issuingJurisdictionCode: String,
        val priorityClass: Int = 0,
        val sequenceWithinClass: Int = 0,
        val initialArrearsCents: Long? = null,
        val currentArrearsCents: Long? = null,
        val supportsOtherDependents: Boolean? = null,
        val arrearsAtLeast12Weeks: Boolean? = null,
        val servedDate: LocalDate = LocalDate.of(2024, 1, 1),
        // Typed override columns (queryable)
        val formulaType: String? = null,
        val percentOfDisposable: Double? = null,
        val fixedAmountCents: Long? = null,
        val protectedFloorCents: Long? = null,
        val protectedMinWageHourlyRateCents: Long? = null,
        val protectedMinWageHours: Double? = null,
        val protectedMinWageMultiplier: Double? = null,
        // JSON escape hatch (still supported)
        val formulaOverride: com.example.uspayroll.payroll.model.garnishment.GarnishmentFormula? = null,
        val protectedEarningsRuleOverride: com.example.uspayroll.payroll.model.garnishment.ProtectedEarningsRule? = null,
    )

    data class Scenario(
        val name: String,
        val employerId: EmployerId,
        val employeeId: EmployeeId,
        val seedOrders: List<SeedOrder>,
        val expectedOrderId: String,
        val expectedType: GarnishmentType,
        val expectedAmountCents: Long? = null,
        val expectProtectedFloor: Boolean = false,
        val expectedFloorCents: Long? = null,
    )

    companion object {
        @JvmStatic
        fun garnishmentScenarios(): List<Scenario> = listOf(
            // Creditor garnishments
            Scenario(
                name = "CA creditor garnishment",
                employerId = EmployerId("EMP-GARN-CA"),
                employeeId = EmployeeId("EE-CA-CRED"),
                seedOrders = listOf(
                    SeedOrder(
                        orderId = "ORDER-CA-CRED-1",
                        type = GarnishmentType.CREDITOR_GARNISHMENT,
                        issuingJurisdictionType = "STATE",
                        issuingJurisdictionCode = "CA",
                    ),
                ),
                expectedOrderId = "ORDER-CA-CRED-1",
                expectedType = GarnishmentType.CREDITOR_GARNISHMENT,
                // 10% of $2,000.00 gross = $200.00
                expectedAmountCents = 20_000L,
            ),
            Scenario(
                name = "CA creditor garnishment uses typed per-order formula override when present",
                employerId = EmployerId("EMP-GARN-CA"),
                employeeId = EmployeeId("EE-CA-CRED-OVERRIDE"),
                seedOrders = listOf(
                    SeedOrder(
                        orderId = "ORDER-CA-CRED-OVERRIDE-1",
                        type = GarnishmentType.CREDITOR_GARNISHMENT,
                        issuingJurisdictionType = "STATE",
                        issuingJurisdictionCode = "CA",
                        formulaType = "FIXED_AMOUNT_PER_PERIOD",
                        fixedAmountCents = 12_345L,
                    ),
                ),
                expectedOrderId = "ORDER-CA-CRED-OVERRIDE-1",
                expectedType = GarnishmentType.CREDITOR_GARNISHMENT,
                expectedAmountCents = 12_345L,
            ),
            Scenario(
                name = "NV creditor garnishment",
                employerId = EmployerId("EMP-GARN-NV"),
                employeeId = EmployeeId("EE-NV-CRED"),
                seedOrders = listOf(
                    SeedOrder(
                        orderId = "ORDER-NV-CRED-1",
                        type = GarnishmentType.CREDITOR_GARNISHMENT,
                        issuingJurisdictionType = "STATE",
                        issuingJurisdictionCode = "NV",
                    ),
                ),
                expectedOrderId = "ORDER-NV-CRED-1",
                expectedType = GarnishmentType.CREDITOR_GARNISHMENT,
                expectedAmountCents = 20_000L,
            ),
            Scenario(
                name = "NY creditor garnishment",
                employerId = EmployerId("EMP-GARN-NY"),
                employeeId = EmployeeId("EE-NY-CRED"),
                seedOrders = listOf(
                    SeedOrder(
                        orderId = "ORDER-NY-CRED-1",
                        type = GarnishmentType.CREDITOR_GARNISHMENT,
                        issuingJurisdictionType = "STATE",
                        issuingJurisdictionCode = "NY",
                    ),
                ),
                expectedOrderId = "ORDER-NY-CRED-1",
                expectedType = GarnishmentType.CREDITOR_GARNISHMENT,
                // EMP-GARN-NY has an employer-specific NY creditor rule at 15%.
                expectedAmountCents = 30_000L,
            ),

            // State tax levies (levy-with-bands)
            Scenario(
                name = "CA state tax levy",
                employerId = EmployerId("EMP-GARN-CA"),
                employeeId = EmployeeId("EE-CA-STLEVY"),
                seedOrders = listOf(
                    SeedOrder(
                        orderId = "ORDER-CA-STLEVY-1",
                        type = GarnishmentType.STATE_TAX_LEVY,
                        issuingJurisdictionType = "STATE",
                        issuingJurisdictionCode = "CA",
                    ),
                ),
                expectedOrderId = "ORDER-CA-STLEVY-1",
                expectedType = GarnishmentType.STATE_TAX_LEVY,
                // Disposable 2,000.00, CA SINGLE exempt 600.00 (first band) => levy 1,400.00
                expectedAmountCents = 140_000L,
            ),
            Scenario(
                name = "NV state tax levy",
                employerId = EmployerId("EMP-GARN-NV"),
                employeeId = EmployeeId("EE-NV-STLEVY"),
                seedOrders = listOf(
                    SeedOrder(
                        orderId = "ORDER-NV-STLEVY-1",
                        type = GarnishmentType.STATE_TAX_LEVY,
                        issuingJurisdictionType = "STATE",
                        issuingJurisdictionCode = "NV",
                    ),
                ),
                expectedOrderId = "ORDER-NV-STLEVY-1",
                expectedType = GarnishmentType.STATE_TAX_LEVY,
                // Disposable 2,000.00, NV SINGLE exempt 500.00 => levy 1,500.00
                expectedAmountCents = 150_000L,
            ),
            Scenario(
                name = "NY state tax levy",
                employerId = EmployerId("EMP-GARN-NY"),
                employeeId = EmployeeId("EE-NY-STLEVY"),
                seedOrders = listOf(
                    SeedOrder(
                        orderId = "ORDER-NY-STLEVY-1",
                        type = GarnishmentType.STATE_TAX_LEVY,
                        issuingJurisdictionType = "STATE",
                        issuingJurisdictionCode = "NY",
                    ),
                ),
                expectedOrderId = "ORDER-NY-STLEVY-1",
                expectedType = GarnishmentType.STATE_TAX_LEVY,
                // Disposable 2,000.00, NY SINGLE exempt 700.00 => levy 1,300.00
                expectedAmountCents = 130_000L,
            ),

            // Federal tax levy (levy-with-bands)
            Scenario(
                name = "federal tax levy",
                employerId = EmployerId("EMP-GARN-CA"),
                employeeId = EmployeeId("EE-CA-FEDLEVY"),
                seedOrders = listOf(
                    SeedOrder(
                        orderId = "ORDER-FEDLEVY-1",
                        type = GarnishmentType.FEDERAL_TAX_LEVY,
                        issuingJurisdictionType = "FEDERAL",
                        issuingJurisdictionCode = "US",
                    ),
                ),
                expectedOrderId = "ORDER-FEDLEVY-1",
                expectedType = GarnishmentType.FEDERAL_TAX_LEVY,
                // Disposable 2,000.00, FED SINGLE exempt 400.00 (first band) => levy 1,600.00
                expectedAmountCents = 160_000L,
            ),

            // Student loans
            Scenario(
                name = "CA student loan garnishment",
                employerId = EmployerId("EMP-GARN-CA"),
                employeeId = EmployeeId("EE-CA-STUDENT"),
                seedOrders = listOf(
                    SeedOrder(
                        orderId = "ORDER-CA-STUDENT-1",
                        type = GarnishmentType.STUDENT_LOAN,
                        issuingJurisdictionType = "FEDERAL",
                        issuingJurisdictionCode = "US",
                    ),
                ),
                expectedOrderId = "ORDER-CA-STUDENT-1",
                expectedType = GarnishmentType.STUDENT_LOAN,
                expectedAmountCents = null,
            ),
            Scenario(
                name = "NV student loan garnishment",
                employerId = EmployerId("EMP-GARN-NV"),
                employeeId = EmployeeId("EE-NV-STUDENT"),
                seedOrders = listOf(
                    SeedOrder(
                        orderId = "ORDER-NV-STUDENT-1",
                        type = GarnishmentType.STUDENT_LOAN,
                        issuingJurisdictionType = "FEDERAL",
                        issuingJurisdictionCode = "US",
                    ),
                ),
                expectedOrderId = "ORDER-NV-STUDENT-1",
                expectedType = GarnishmentType.STUDENT_LOAN,
                expectedAmountCents = null,
            ),
            Scenario(
                name = "NY student loan garnishment",
                employerId = EmployerId("EMP-GARN-NY"),
                employeeId = EmployeeId("EE-NY-STUDENT"),
                seedOrders = listOf(
                    SeedOrder(
                        orderId = "ORDER-NY-STUDENT-1",
                        type = GarnishmentType.STUDENT_LOAN,
                        issuingJurisdictionType = "FEDERAL",
                        issuingJurisdictionCode = "US",
                    ),
                ),
                expectedOrderId = "ORDER-NY-STUDENT-1",
                expectedType = GarnishmentType.STUDENT_LOAN,
                expectedAmountCents = null,
            ),

            // Employer-specific override remains supported (exercise LESSER_OF_PERCENT_OR_AMOUNT mapping)
            Scenario(
                name = "employer override lesser-of creditor rule (CA)",
                employerId = EmployerId("EMP-GARN-OVERRIDE"),
                employeeId = EmployeeId("EE-HR-OVERRIDE-E2E"),
                seedOrders = emptyList(),
                expectedOrderId = "ORDER-RULE-1",
                expectedType = GarnishmentType.CREDITOR_GARNISHMENT,
                // Annual 52,000 / 26 = 2,000 per period. Lesser of 25% (500) or 1,500.
                expectedAmountCents = 50_000L,
                expectProtectedFloor = false,
                expectedFloorCents = null,
            ),
        )
    }

    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var payrollRunService: PayrollRunService

    @Autowired
    lateinit var hrClientProperties: HrClientProperties

    @Autowired
    lateinit var objectMapper: ObjectMapper

    private val payPeriodId = "2025-01-BW1"
    private val checkDate: LocalDate = LocalDate.of(2025, 1, 15)

    @BeforeEach
    fun pointHrClientAtEmbeddedService() {
        hrClientProperties.baseUrl = "http://localhost:$port"
    }

    private fun seedEmployerData(scenario: Scenario) {
        val employerId = scenario.employerId
        val employeeId = scenario.employeeId

        jdbcTemplate.update("DELETE FROM garnishment_withholding_event")
        jdbcTemplate.update("DELETE FROM garnishment_ledger")
        jdbcTemplate.update("DELETE FROM garnishment_order")
        jdbcTemplate.update("DELETE FROM employment_compensation")
        jdbcTemplate.update("DELETE FROM employee_profile_effective")
        jdbcTemplate.update("DELETE FROM employee")
        jdbcTemplate.update("DELETE FROM pay_period")

        val (homeState, workState, workCity) = when (employerId.value) {
            "EMP-GARN-CA" -> Triple("CA", "CA", "San Francisco")
            "EMP-GARN-NV" -> Triple("NV", "NV", "Las Vegas")
            "EMP-GARN-NY" -> Triple("NY", "NY", "New York")
            else -> Triple("CA", "CA", "San Francisco")
        }

        val hireDate = LocalDate.of(2024, 6, 1)

        jdbcTemplate.update(
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
            ) VALUES (?, ?, ?, ?, ?, 'SINGLE', 'REGULAR', ?, NULL, 0,
                      FALSE, FALSE,
                      NULL, NULL, NULL,
                      FALSE,
                      NULL,
                      FALSE, TRUE, 'NON_EXEMPT', FALSE)
            """.trimIndent(),
            employerId.value,
            employeeId.value,
            homeState,
            workState,
            workCity,
            hireDate,
        )

        jdbcTemplate.update(
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
            ) VALUES (?, ?, ?, ?, ?, ?, ?, 'SINGLE', 'REGULAR', ?, NULL, 0,
                      FALSE, FALSE,
                      FALSE,
                      NULL,
                      FALSE, TRUE, 'NON_EXEMPT', FALSE)
            """.trimIndent(),
            employerId.value,
            employeeId.value,
            hireDate,
            LocalDate.of(9999, 12, 31),
            homeState,
            workState,
            workCity,
            hireDate,
        )

        jdbcTemplate.update(
            """
            INSERT INTO employment_compensation (
              employer_id, employee_id,
              effective_from, effective_to,
              compensation_type,
              annual_salary_cents, hourly_rate_cents, pay_frequency
            ) VALUES (?, ?, ?, ?, 'SALARIED', 52_00000, NULL, 'BIWEEKLY')
            """.trimIndent(),
            employerId.value,
            employeeId.value,
            LocalDate.of(2024, 6, 1),
            LocalDate.of(9999, 12, 31),
        )

        jdbcTemplate.update(
            """
            INSERT INTO pay_period (
              employer_id, id,
              start_date, end_date, check_date,
              frequency, sequence_in_year
            ) VALUES (?, ?, ?, ?, ?, 'BIWEEKLY', 1)
            """.trimIndent(),
            employerId.value,
            payPeriodId,
            LocalDate.of(2025, 1, 1),
            LocalDate.of(2025, 1, 14),
            checkDate,
        )

        scenario.seedOrders.forEach { o ->
            val formulaJson = o.formulaOverride?.let { objectMapper.writeValueAsString(it) }
            val protectedJson = o.protectedEarningsRuleOverride?.let { objectMapper.writeValueAsString(it) }

            jdbcTemplate.update(
                """
                INSERT INTO garnishment_order (
                  employer_id, employee_id, order_id,
                  type, issuing_jurisdiction_type, issuing_jurisdiction_code,
                  case_number, status,
                  served_date, end_date,
                  priority_class, sequence_within_class,
                  initial_arrears_cents, current_arrears_cents,
                  supports_other_dependents, arrears_at_least_12_weeks,
                  -- typed overrides
                  formula_type, percent_of_disposable, fixed_amount_cents,
                  protected_floor_cents,
                  protected_min_wage_hourly_rate_cents, protected_min_wage_hours, protected_min_wage_multiplier,
                  -- json escape hatch
                  formula_json, protected_earnings_rule_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                employerId.value,
                employeeId.value,
                o.orderId,
                o.type.name,
                o.issuingJurisdictionType,
                o.issuingJurisdictionCode,
                "CASE-${o.orderId}",
                "ACTIVE",
                o.servedDate,
                null,
                o.priorityClass,
                o.sequenceWithinClass,
                o.initialArrearsCents,
                o.currentArrearsCents,
                o.supportsOtherDependents,
                o.arrearsAtLeast12Weeks,
                o.formulaType,
                o.percentOfDisposable,
                o.fixedAmountCents,
                o.protectedFloorCents,
                o.protectedMinWageHourlyRateCents,
                o.protectedMinWageHours,
                o.protectedMinWageMultiplier,
                formulaJson,
                protectedJson,
            )
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("garnishmentScenarios")
    fun `hr-service JSON-driven garnishment rules are applied end-to-end`(scenario: Scenario) {
        seedEmployerData(scenario)

        val results = payrollRunService.runHrBackedPayForPeriod(
            employerId = scenario.employerId,
            payPeriodId = payPeriodId,
            employeeIds = listOf(scenario.employeeId),
        )

        assertEquals(1, results.size)
        val paycheck = results.first()

        // Sanity: paycheck is for the expected employee and has positive gross.
        assertEquals(scenario.employeeId, paycheck.employeeId)
        assertTrue(paycheck.gross.amount > 0L)

        val garnishmentsByCode = paycheck.deductions.associateBy { it.code.value }
        val garn = garnishmentsByCode[scenario.expectedOrderId]
        assertNotNull(garn, "Expected garnishment for ${scenario.expectedOrderId} to be present")

        val garnishmentEvents = paycheck.trace.steps.filterIsInstance<TraceStep.GarnishmentApplied>()
        val ga = garnishmentEvents.singleOrNull { it.orderId == scenario.expectedOrderId }
        kotlin.test.assertNotNull(ga)
        assertEquals(scenario.expectedType.name, ga!!.type)

        // Most scenarios here have a single order with no competing priority,
        // so requested == applied.
        assertEquals(ga.requestedCents, ga.appliedCents)

        scenario.expectedAmountCents?.let { expected ->
            assertEquals(expected, garn.amount.amount)
            assertEquals(expected, ga.appliedCents)
        }

        // Student loan scenarios: assert the 15% ceiling applied to the
        // student-loan disposable base (gross - mandatory pre-tax - employee taxes).
        if (scenario.expectedType == GarnishmentType.STUDENT_LOAN) {
            val di = paycheck.trace.steps
                .filterIsInstance<TraceStep.DisposableIncomeComputed>()
                .singleOrNull { it.orderId == scenario.expectedOrderId }
            kotlin.test.assertNotNull(di)

            val expected = (di!!.baseDisposableCents * 0.15).toLong()
            assertEquals(expected, garn.amount.amount)
            assertEquals(expected, ga.appliedCents)
        }

        val protectedSteps = paycheck.trace.steps.filterIsInstance<TraceStep.ProtectedEarningsApplied>()
        val stepForOrder = protectedSteps.singleOrNull { it.orderId == scenario.expectedOrderId }

        if (scenario.expectProtectedFloor) {
            assertNotNull(stepForOrder, "Expected ProtectedEarningsApplied trace for ${scenario.expectedOrderId}")
            scenario.expectedFloorCents?.let { floor ->
                assertEquals(floor, stepForOrder!!.floorCents)
            }
            assertTrue(stepForOrder!!.adjustedCents <= stepForOrder.requestedCents)
        } else {
            assertNull(stepForOrder, "Did not expect ProtectedEarningsApplied trace for ${scenario.expectedOrderId}")
        }
    }
}
