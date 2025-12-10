package com.example.uspayroll.worker

import com.example.uspayroll.payroll.model.*
import com.example.uspayroll.shared.EmployeeId
import com.example.uspayroll.shared.EmployerId
import com.example.uspayroll.shared.Money
import com.fasterxml.jackson.databind.ObjectMapper
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
}