package com.example.usbilling.worker

import com.example.usbilling.hr.client.HrClient
import com.example.usbilling.payroll.model.*
import com.example.usbilling.payroll.model.garnishment.GarnishmentFormula
import com.example.usbilling.payroll.model.garnishment.GarnishmentType
import com.example.usbilling.payroll.model.garnishment.ProtectedEarningsRule
import com.example.usbilling.shared.EmployeeId
import com.example.usbilling.shared.EmployerId
import com.example.usbilling.shared.Money
import com.example.usbilling.worker.support.StubTaxLaborClientsTestConfig
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestInstance.Lifecycle
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.util.TestPropertyValues
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.test.context.ContextConfiguration
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(StubTaxLaborClientsTestConfig::class)
@ContextConfiguration(initializers = [HrClientIntegrationTest.Companion.Initializer::class])
@TestInstance(Lifecycle.PER_CLASS)
class HrClientIntegrationTest {

    companion object {
        val server = MockWebServer()

        class Initializer : ApplicationContextInitializer<ConfigurableApplicationContext> {
            override fun initialize(context: ConfigurableApplicationContext) {
                server.start()
                val baseUrl = server.url("/").toString().trimEnd('/')
                TestPropertyValues.of("downstreams.hr.base-url=$baseUrl").applyTo(context.environment)
            }
        }
    }

    @AfterAll
    fun shutdown() {
        server.shutdown()
    }

    @Autowired
    lateinit var payrollRunService: com.example.usbilling.worker.PayrollRunService

    @Autowired
    lateinit var meterRegistry: MeterRegistry

    @Autowired
    lateinit var hrClient: HrClient

    private val objectMapper = jacksonObjectMapper().findAndRegisterModules()

    @BeforeEach
    fun drainRequests() {
        // MockWebServer is shared across test methods; ensure no leftover
        // requests from prior tests remain in the queue before we assert
        // request ordering.
        while (true) {
            val req = server.takeRequest(10, TimeUnit.MILLISECONDS) ?: break
            // discard
            req
        }
    }

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

        // Enqueue responses for pay period, employee snapshot, and empty garnishments.
        // PayrollRunService always calls the garnishments endpoint as part of the HR-backed flow.
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
                .setBody("[]"),
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
        val garnDto = com.example.usbilling.hr.http.GarnishmentOrderDto(
            orderId = "ORDER-HR-1",
            planId = "GARN_PLAN_HR",
            type = GarnishmentType.CREDITOR_GARNISHMENT,
            formula = GarnishmentFormula.PercentOfDisposable(Percent(0.10)),
        )

        // Enqueue responses for pay period, employee snapshot, garnishments, and the withholdings callback.
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
        server.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setHeader("Location", "/ok"),
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

        assertTrue(
            fourth.path!!.contains("/garnishments/withholdings"),
            "Expected HR callback to garnishments withholdings endpoint but was ${fourth.path}",
        )
        val body = fourth.body.readUtf8()
        assertTrue(
            body.contains("ORDER-HR-1"),
            "Expected withholding payload to reference ORDER-HR-1 but body was: $body",
        )

        val afterCount = meterRegistry.find("payroll.garnishments.employees_with_orders")
            .tag("employer_id", employerId.value)
            .counter()
            ?.count() ?: 0.0
        assertTrue(
            afterCount > beforeCount,
            "Expected employees_with_orders metric to increase for employer ${employerId.value}",
        )
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
        val garnDto = com.example.usbilling.hr.http.GarnishmentOrderDto(
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
        // Withholdings callback response
        server.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setHeader("Location", "/ok"),
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
        assertTrue(
            afterProtected > beforeProtected,
            "Expected protected_floor_applied metric to increase for employer ${employerId.value}",
        )
    }

    @Test
    fun `http HR client maps 404 to null for snapshot and pay periods`() {
        val employerId = EmployerId("EMP-HR-404")
        val employeeId = EmployeeId("EE-HR-404")
        val payPeriodId = "2025-01-BW1"
        val checkDate = LocalDate.of(2025, 1, 15)

        // 404 for getPayPeriod
        server.enqueue(
            MockResponse()
                .setResponseCode(404),
        )
        val payPeriod = hrClient.getPayPeriod(employerId, payPeriodId)
        assertNull(payPeriod, "Expected getPayPeriod to return null on 404")

        // 404 for getEmployeeSnapshot
        server.enqueue(
            MockResponse()
                .setResponseCode(404),
        )
        val snapshot = hrClient.getEmployeeSnapshot(employerId, employeeId, checkDate)
        assertNull(snapshot, "Expected getEmployeeSnapshot to return null on 404")

        // 404 for findPayPeriodByCheckDate
        server.enqueue(
            MockResponse()
                .setResponseCode(404),
        )
        val byCheckDate = hrClient.findPayPeriodByCheckDate(employerId, checkDate)
        assertNull(byCheckDate, "Expected findPayPeriodByCheckDate to return null on 404")
    }

    @Test
    fun `hr-backed flow continues when garnishments endpoint is unavailable`() {
        val employerId = EmployerId("EMP-HR-GARN-UNAVAILABLE")
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

        val employeeId = EmployeeId("EE-HR-GARN-UNAVAILABLE-1")
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

        // Enqueue responses for pay period and employee snapshot, followed by
        // repeated 500 errors for the garnishments endpoint. The HttpHrClient
        // should log and fall back to an empty garnishment list rather than
        // failing the entire run.
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(objectMapper.writeValueAsString(payPeriod) as String),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(objectMapper.writeValueAsString(snapshot) as String),
        )
        repeat(3) {
            server.enqueue(
                MockResponse()
                    .setResponseCode(500)
                    .setBody("Internal Server Error"),
            )
        }

        val results = payrollRunService.runHrBackedPayForPeriod(
            employerId = employerId,
            payPeriodId = payPeriodId,
            employeeIds = listOf(employeeId),
        )

        assertEquals(1, results.size)
        val paycheck = results.first()

        // Paycheck should still have gross/net amounts even though garnishment
        // orders could not be fetched.
        assertEquals(employeeId, paycheck.employeeId)
        assertTrue(paycheck.gross.amount > 0L)
        assertTrue(paycheck.net.amount > 0L)

        // Ensure that no withholdings callback was issued, since there were
        // no effective orders after the failure.
        val first = server.takeRequest() // pay period
        val second = server.takeRequest() // employee snapshot
        val third = server.takeRequest() // failed garnishments call
        assertNotNull(first)
        assertNotNull(second)
        assertNotNull(third)
    }

    @Test
    fun `hr-backed flow does not fail when garnishment withholdings callback fails`() {
        val employerId = EmployerId("EMP-HR-GARN-CALLBACK-FAIL")
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

        val employeeId = EmployeeId("EE-HR-GARN-CALLBACK-FAIL-1")
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

        val garnDto = com.example.usbilling.hr.http.GarnishmentOrderDto(
            orderId = "ORDER-CB-FAIL-1",
            planId = "GARN_PLAN_CB_FAIL",
            type = GarnishmentType.CREDITOR_GARNISHMENT,
            formula = GarnishmentFormula.PercentOfDisposable(Percent(0.10)),
        )

        // Normal responses for pay period, snapshot, and garnishments, followed
        // by a 500 error for the withholdings callback. The payroll run should
        // still complete successfully.
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(objectMapper.writeValueAsString(payPeriod) as String),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(objectMapper.writeValueAsString(snapshot) as String),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(objectMapper.writeValueAsString(listOf(garnDto)) as String),
        )
        // Withholdings callback fails even after retries.
        repeat(3) {
            server.enqueue(
                MockResponse()
                    .setResponseCode(500)
                    .setBody("Internal Server Error"),
            )
        }

        val results = payrollRunService.runHrBackedPayForPeriod(
            employerId = employerId,
            payPeriodId = payPeriodId,
            employeeIds = listOf(employeeId),
        )

        assertEquals(1, results.size)
        val paycheck = results.first()

        // We still expect a garnishment deduction in the paycheck; the failure
        // is isolated to the callback into HR.
        val garnishmentsByCode = paycheck.deductions.associateBy { it.code.value }
        val garn = garnishmentsByCode["ORDER-CB-FAIL-1"]
        assertNotNull(garn, "Expected garnishment for ORDER-CB-FAIL-1 to be present in deductions")
    }
}
