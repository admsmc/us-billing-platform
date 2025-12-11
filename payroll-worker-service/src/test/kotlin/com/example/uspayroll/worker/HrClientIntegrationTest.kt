package com.example.uspayroll.worker

import com.example.uspayroll.payroll.model.*
import com.example.uspayroll.payroll.model.garnishment.GarnishmentFormula
import com.example.uspayroll.payroll.model.garnishment.GarnishmentType
import com.example.uspayroll.payroll.model.garnishment.ProtectedEarningsRule
import com.example.uspayroll.shared.EmployeeId
import com.example.uspayroll.shared.EmployerId
import com.example.uspayroll.shared.Money
import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestInstance.Lifecycle
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.util.TestPropertyValues
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.test.context.ContextConfiguration
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ContextConfiguration(initializers = [HrClientIntegrationTest.Companion.Initializer::class])
@TestInstance(Lifecycle.PER_CLASS)
class HrClientIntegrationTest {

    companion object {
        val server = MockWebServer()

        class Initializer : ApplicationContextInitializer<ConfigurableApplicationContext> {
            override fun initialize(context: ConfigurableApplicationContext) {
                server.start()
                val baseUrl = server.url("/").toString().trimEnd('/')
                TestPropertyValues.of("hr.base-url=$baseUrl").applyTo(context.environment)
            }
        }
    }

    @AfterAll
    fun shutdown() {
        server.shutdown()
    }

    @Autowired
    lateinit var payrollRunService: com.example.uspayroll.worker.PayrollRunService

    @Autowired
    lateinit var meterRegistry: MeterRegistry

    private val objectMapper = ObjectMapper()

    @Test
    fun `computes paycheck using HTTP-backed HR client`() {
        val employerId = EmployerId("EMP-HR-HTTP")
        val payPeriodId = "2025-01-BW1"
        val checkDate = LocalDate.of(2025, 1, 15)

        val payPeriod = PayPeriod(
            id = payPeriodId,
            employerId = employerId,
            dateRange = LocalDateRange(
                startInclusive = LocalDate.of(2025, 1, 1),
                endInclusive = LocalDate.of(2025, 1, 14),
            ),
            checkDate = checkDate,
            frequency = PayFrequency.BIWEEKLY,
        )

        val employeeId = EmployeeId("EE-HR-1")
        val snapshot = EmployeeSnapshot(
            employerId = employerId,
            employeeId = employeeId,
            homeState = "CA",
            workState = "CA",
            filingStatus = FilingStatus.SINGLE,
            employmentType = EmploymentType.REGULAR,
            baseCompensation = BaseCompensation.Salaried(
                annualSalary = Money(52_000_00L),
                frequency = PayFrequency.BIWEEKLY,
            ),
        )

        // Enqueue responses for pay period and employee snapshot
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(objectMapper.writeValueAsString(payPeriod) as String)
        )
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(objectMapper.writeValueAsString(snapshot) as String)
        )

        val results = payrollRunService.runHrBackedPayForPeriod(
            employerId = employerId,
            payPeriodId = payPeriodId,
            employeeIds = listOf(employeeId),
        )

        assertEquals(1, results.size)
        val paycheck = results.first()

        assertEquals(employeeId, paycheck.employeeId)
        assertTrue(paycheck.gross.amount > 0L)
        assertTrue(paycheck.net.amount > 0L)
    }

    @Test
    fun `hr-backed flow wires garnishment orders into paycheck deductions`() {
        val employerId = EmployerId("EMP-HR-GARN")
        val payPeriodId = "2025-01-BW1"
        val checkDate = LocalDate.of(2025, 1, 15)

        val payPeriod = PayPeriod(
            id = payPeriodId,
            employerId = employerId,
            dateRange = LocalDateRange(
                startInclusive = LocalDate.of(2025, 1, 1),
                endInclusive = LocalDate.of(2025, 1, 14),
            ),
            checkDate = checkDate,
            frequency = PayFrequency.BIWEEKLY,
        )

        val employeeId = EmployeeId("EE-HR-GARN-1")
        val snapshot = EmployeeSnapshot(
            employerId = employerId,
            employeeId = employeeId,
            homeState = "CA",
            workState = "CA",
            filingStatus = FilingStatus.SINGLE,
            employmentType = EmploymentType.REGULAR,
            baseCompensation = BaseCompensation.Salaried(
                annualSalary = Money(52_000_00L),
                frequency = PayFrequency.BIWEEKLY,
            ),
        )

        // Simple percent-of-disposable garnishment order DTO
        val garnDto = com.example.uspayroll.worker.client.GarnishmentOrderDto(
            orderId = "ORDER-HR-1",
            planId = "GARN_PLAN_HR",
            type = GarnishmentType.CREDITOR_GARNISHMENT,
            formula = GarnishmentFormula.PercentOfDisposable(Percent(0.10)),
        )

        // Enqueue responses for pay period, employee snapshot, and garnishments
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(objectMapper.writeValueAsString(payPeriod) as String),
        )
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(objectMapper.writeValueAsString(snapshot) as String),
        )
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(objectMapper.writeValueAsString(listOf(garnDto)) as String),
        )

        val beforeCount = meterRegistry.find("payroll.garnishments.employees_with_orders")
            .tag("employer_id", employerId.value)
            .counter()
            ?.count() ?: 0.0

        val results = payrollRunService.runHrBackedPayForPeriod(
            employerId = employerId,
            payPeriodId = payPeriodId,
            employeeIds = listOf(employeeId),
        )

        assertEquals(1, results.size)
        val paycheck = results.first()

        val garnishmentsByCode = paycheck.deductions.associateBy { it.code.value }
        // Order-based code is derived from order id in GarnishmentsCalculator.
        val garn = garnishmentsByCode["ORDER-HR-1"]
        kotlin.test.assertNotNull(garn, "Expected garnishment for ORDER-HR-1 to be present in deductions")

        // Verify that worker-service called back to HR to record the
        // garnishment withholding. There should be a fourth HTTP request to the
        // withholdings endpoint after pay period, snapshot, and orders.
        val first = server.takeRequest() // pay period
        val second = server.takeRequest() // employee snapshot
        val third = server.takeRequest() // garnishments
        val fourth = server.takeRequest() // withholdings callback

        assertTrue(fourth.path!!.contains("/garnishments/withholdings"),
            "Expected HR callback to garnishments withholdings endpoint but was ${fourth.path}")
        val body = fourth.body.readUtf8()
        assertTrue(body.contains("ORDER-HR-1"),
            "Expected withholding payload to reference ORDER-HR-1 but body was: $body")

        val afterCount = meterRegistry.find("payroll.garnishments.employees_with_orders")
            .tag("employer_id", employerId.value)
            .counter()
            ?.count() ?: 0.0
        assertTrue(afterCount > beforeCount,
            "Expected employees_with_orders metric to increase for employer ${employerId.value}")
    }

    @Test
    fun `hr-backed flow applies child support protected earnings floor`() {
        val employerId = EmployerId("EMP-HR-CS")
        val payPeriodId = "2025-01-BW1"
        val checkDate = LocalDate.of(2025, 1, 15)

        val payPeriod = PayPeriod(
            id = payPeriodId,
            employerId = employerId,
            dateRange = LocalDateRange(
                startInclusive = LocalDate.of(2025, 1, 1),
                endInclusive = LocalDate.of(2025, 1, 14),
            ),
            checkDate = checkDate,
            frequency = PayFrequency.BIWEEKLY,
        )

        val employeeId = EmployeeId("EE-HR-CS-1")
        val snapshot = EmployeeSnapshot(
            employerId = employerId,
            employeeId = employeeId,
            homeState = "CA",
            workState = "CA",
            filingStatus = FilingStatus.SINGLE,
            employmentType = EmploymentType.REGULAR,
            baseCompensation = BaseCompensation.Salaried(
                annualSalary = Money(52_000_00L),
                frequency = PayFrequency.BIWEEKLY,
            ),
        )

        val protectedFloor = Money(3_000_00L)

        // CHILD_SUPPORT order that wants 60% of disposable income but is
        // subject to a protected earnings floor. Given the relatively modest
        // salary here, the floor should bind and reduce the requested amount.
        val garnDto = com.example.uspayroll.worker.client.GarnishmentOrderDto(
            orderId = "ORDER-CS-1",
            planId = "GARN_PLAN_CHILD_SUPPORT",
            type = GarnishmentType.CHILD_SUPPORT,
            formula = GarnishmentFormula.PercentOfDisposable(Percent(0.60)),
            protectedEarningsRule = ProtectedEarningsRule.FixedFloor(protectedFloor),
        )

        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(objectMapper.writeValueAsString(payPeriod) as String),
        )
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(objectMapper.writeValueAsString(snapshot) as String),
        )
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(objectMapper.writeValueAsString(listOf(garnDto)) as String),
        )

        val beforeProtected = meterRegistry.find("payroll.garnishments.protected_floor_applied")
            .tag("employer_id", employerId.value)
            .counter()
            ?.count() ?: 0.0

        val results = payrollRunService.runHrBackedPayForPeriod(
            employerId = employerId,
            payPeriodId = payPeriodId,
            employeeIds = listOf(employeeId),
        )

        assertEquals(1, results.size)
        val paycheck = results.first()

        // The protected earnings floor should be reflected in the trace.
        val protectedSteps = paycheck.trace.steps.filterIsInstance<TraceStep.ProtectedEarningsApplied>()
        val csStep = protectedSteps.singleOrNull { it.orderId == "ORDER-CS-1" }
        kotlin.test.assertNotNull(csStep, "Expected ProtectedEarningsApplied trace for ORDER-CS-1")
        assertEquals(protectedFloor.amount, csStep!!.floorCents)
        // Adjusted cents should never exceed the originally requested amount.
        assertTrue(csStep.adjustedCents <= csStep.requestedCents)

        val afterProtected = meterRegistry.find("payroll.garnishments.protected_floor_applied")
            .tag("employer_id", employerId.value)
            .counter()
            ?.count() ?: 0.0
        assertTrue(afterProtected > beforeProtected,
            "Expected protected_floor_applied metric to increase for employer ${employerId.value}")
    }
}
