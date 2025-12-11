package com.example.uspayroll.worker

import com.example.uspayroll.hr.HrApplication
import com.example.uspayroll.payroll.model.TraceStep
import com.example.uspayroll.shared.EmployeeId
import com.example.uspayroll.shared.EmployerId
import com.example.uspayroll.worker.client.HrClientProperties
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestInstance.Lifecycle
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
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
)
@TestInstance(Lifecycle.PER_CLASS)
class WorkerHrGarnishmentParamE2ETest {

    data class Scenario(
        val name: String,
        val employerId: EmployerId,
        val employeeId: EmployeeId,
        val expectedOrderId: String,
        val expectedAmountCents: Long?,
        val expectProtectedFloor: Boolean,
        val expectedFloorCents: Long?,
    )

    companion object {
        @JvmStatic
        fun garnishmentScenarios(): List<Scenario> = listOf(
            Scenario(
                name = "generic CHILD_SUPPORT with protected floor",
                employerId = EmployerId("EMP-HR-HTTP-INT"),
                employeeId = EmployeeId("EE-HR-CS-E2E"),
                expectedOrderId = "ORDER-RULE-2",
                expectedAmountCents = null, // Amount is scenario-dependent; we assert floor + trace only.
                expectProtectedFloor = true,
                expectedFloorCents = 300_000L,
            ),
            Scenario(
                name = "employer override lesser-of creditor rule",
                employerId = EmployerId("EMP-GARN-OVERRIDE"),
                employeeId = EmployeeId("EE-HR-OVERRIDE-E2E"),
                expectedOrderId = "ORDER-RULE-1",
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

    private val payPeriodId = "2025-01-BW1"
    private val checkDate: LocalDate = LocalDate.of(2025, 1, 15)

    @BeforeEach
    fun pointHrClientAtEmbeddedService() {
        hrClientProperties.baseUrl = "http://localhost:$port"
    }

    private fun seedEmployerData(employerId: EmployerId, employeeId: EmployeeId) {
        jdbcTemplate.update("DELETE FROM employment_compensation")
        jdbcTemplate.update("DELETE FROM employee")
        jdbcTemplate.update("DELETE FROM pay_period")

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
            ) VALUES (?, ?, 'CA', 'CA', 'San Francisco', 'SINGLE', 'REGULAR', ?, NULL, 0,
                      FALSE, FALSE,
                      NULL, NULL, NULL,
                      FALSE,
                      NULL,
                      FALSE, TRUE, 'NON_EXEMPT', FALSE)
            """.trimIndent(),
            employerId.value,
            employeeId.value,
            LocalDate.of(2024, 6, 1),
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
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("garnishmentScenarios")
    fun `hr-service JSON-driven garnishment rules are applied end-to-end`(scenario: Scenario) {
        seedEmployerData(scenario.employerId, scenario.employeeId)

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

        scenario.expectedAmountCents?.let { expected ->
            assertEquals(expected, garn.amount.amount)
        }

        val protectedSteps = paycheck.trace.steps.filterIsInstance<TraceStep.ProtectedEarningsApplied>()
        val stepForOrder = protectedSteps.singleOrNull { it.orderId == scenario.expectedOrderId }

        // GarnishmentApplied steps should be present for any order that
        // produced a deduction.
        val garnishmentEvents = paycheck.trace.steps.filterIsInstance<TraceStep.GarnishmentApplied>()
        kotlin.test.assertTrue(garnishmentEvents.any { it.orderId == scenario.expectedOrderId })

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
