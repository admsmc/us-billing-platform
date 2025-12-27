package com.example.usbilling.worker

import com.example.usbilling.payroll.model.*
import com.example.usbilling.payroll.model.garnishment.GarnishmentFormula
import com.example.usbilling.payroll.model.garnishment.GarnishmentType
import com.example.usbilling.payroll.model.garnishment.ProtectedEarningsRule
import com.example.usbilling.shared.EmployeeId
import com.example.usbilling.shared.EmployerId
import com.example.usbilling.shared.Money
import com.example.usbilling.worker.support.StubTaxLaborClientsTestConfig
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
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
import org.springframework.context.annotation.Import
import org.springframework.test.context.ContextConfiguration
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Integration test focused on the GarnishmentEngineV2 feature flag behavior in
 * worker-service. This uses a dedicated Spring context with an allow-list of
 * enabled employers and verifies that a non-enabled employer still receives
 * garnishment orders from HR but does not apply them or send withholdings
 * callbacks.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(StubTaxLaborClientsTestConfig::class)
@ContextConfiguration(initializers = [GarnishmentFeatureFlagIntegrationTest.Companion.Initializer::class])
@TestInstance(Lifecycle.PER_CLASS)
class GarnishmentFeatureFlagIntegrationTest {

    companion object {
        private val server = MockWebServer()

        class Initializer : ApplicationContextInitializer<ConfigurableApplicationContext> {
            override fun initialize(context: ConfigurableApplicationContext) {
                server.start()
                val baseUrl = server.url("/").toString().trimEnd('/')
                TestPropertyValues.of(
                    "downstreams.hr.base-url=$baseUrl",
                    // Only this employer is opted into the new garnishment
                    // engine for this test context.
                    "worker.garnishments.enabled-employers=EMP-GARN-FLAGGED",
                ).applyTo(context.environment)
            }
        }
    }

    @AfterAll
    fun shutdown() {
        server.shutdown()
    }

    @Autowired
    lateinit var payrollRunService: PayrollRunService

    private val objectMapper = jacksonObjectMapper().findAndRegisterModules()

    @Test
    fun `employer not in feature flag allow-list does not apply garnishments or send withholdings`() {
        val employerId = EmployerId("EMP-GARN-NOFLAG")
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

        val employeeId = EmployeeId("EE-GARN-NOFLAG-1")
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

        val garnDto = com.example.uspayroll.hr.http.GarnishmentOrderDto(
            orderId = "ORDER-FF-1",
            planId = "GARN_PLAN_FF",
            type = GarnishmentType.CREDITOR_GARNISHMENT,
            formula = GarnishmentFormula.PercentOfDisposable(Percent(0.10)),
            protectedEarningsRule = ProtectedEarningsRule.FixedFloor(Money(3_000_00L)),
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

        val results = payrollRunService.runHrBackedPayForPeriod(
            employerId = employerId,
            payPeriodId = payPeriodId,
            employeeIds = listOf(employeeId),
        )

        assertEquals(1, results.size)
        val paycheck = results.first()

        // Garnishment orders were present from HR, but because this employer is
        // not in the enabled-employers list, no garnishment deduction line
        // should be applied.
        val garnishmentsByCode = paycheck.deductions.associateBy { it.code.value }
        val garn = garnishmentsByCode["ORDER-FF-1"]
        assertNull(garn, "Did not expect garnishment deduction for ORDER-FF-1 when engine is disabled for employer")

        // HR should see only three requests: pay period, snapshot, and
        // garnishment orders. There should be no withholdings callback.
        assertEquals(
            3,
            server.requestCount,
            "Expected no garnishment withholdings callback when engine is disabled for employer",
        )
    }
}
