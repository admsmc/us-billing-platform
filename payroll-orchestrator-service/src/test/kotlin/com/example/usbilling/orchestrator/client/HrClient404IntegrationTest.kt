package com.example.usbilling.orchestrator.client

import com.example.usbilling.hr.client.HrClient
import com.example.usbilling.shared.CustomerId
import com.example.usbilling.shared.UtilityId
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
import org.springframework.test.context.ContextConfiguration
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlin.test.assertNull

/**
 * Orchestrator-side integration test that exercises HttpHrClient against a
 * MockWebServer and verifies that 404 responses are translated to null for
 * read-style HR endpoints.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ContextConfiguration(initializers = [HrClient404IntegrationTest.Companion.Initializer::class])
@TestInstance(Lifecycle.PER_CLASS)
class HrClient404IntegrationTest {

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
    lateinit var hrClient: HrClient

    @BeforeEach
    fun drainRequests() {
        // MockWebServer is shared across test methods; ensure no leftover
        // requests from prior tests remain in the queue before we assert
        // request behavior.
        while (true) {
            val req = server.takeRequest(10, TimeUnit.MILLISECONDS) ?: break
            // discard
            req
        }
    }

    @Test
    fun `orchestrator HttpHrClient maps 404 to null for snapshot and pay periods`() {
        val employerId = UtilityId("EMP-HR-404-ORCH")
        val employeeId = CustomerId("EE-HR-404-ORCH")
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
}
