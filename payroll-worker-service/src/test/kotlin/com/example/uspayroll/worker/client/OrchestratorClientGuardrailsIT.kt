package com.example.uspayroll.worker.client

import com.example.uspayroll.web.client.CircuitBreakerOpenException
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
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
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ContextConfiguration(initializers = [OrchestratorClientRetryIT.Companion.Initializer::class])
@TestInstance(Lifecycle.PER_CLASS)
class OrchestratorClientRetryIT {

    companion object {
        lateinit var server: MockWebServer

        class Initializer : ApplicationContextInitializer<ConfigurableApplicationContext> {
            override fun initialize(context: ConfigurableApplicationContext) {
                var call = 0
                server = MockWebServer().apply {
                    dispatcher = object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse {
                            call += 1
                            return if (call == 1) {
                                MockResponse().setResponseCode(500).setBody("{\"error\":\"boom\"}")
                            } else {
                                MockResponse()
                                    .setResponseCode(200)
                                    .addHeader("Content-Type", "application/json")
                                    .setBody(
                                        """
                                        {
                                          "employerId":"EMP",
                                          "payRunId":"PR",
                                          "payPeriodId":"PP",
                                          "status":"RUNNING",
                                          "approvalStatus":null,
                                          "paymentStatus":null,
                                          "counts":{"total":1,"queued":0,"running":1,"succeeded":0,"failed":0},
                                          "failures":[]
                                        }
                                        """.trimIndent(),
                                    )
                            }
                        }
                    }
                }
                server.start()

                val baseUrl = server.url("").toString().removeSuffix("/")

                TestPropertyValues.of(
                    "downstreams.orchestrator.base-url=$baseUrl",
                    "downstreams.orchestrator.max-retries=1",
                    "downstreams.orchestrator.retry-initial-backoff=0ms",
                    "downstreams.orchestrator.retry-max-backoff=0ms",
                    "downstreams.orchestrator.circuit-breaker-enabled=false",
                ).applyTo(context.environment)
            }
        }
    }

    @Autowired
    lateinit var client: OrchestratorClient

    @AfterAll
    fun shutdown() {
        server.shutdown()
    }

    @Test
    fun `retries transient 5xx and succeeds`() {
        val result = client.getStatus(employerId = com.example.uspayroll.shared.EmployerId("EMP"), payRunId = "PR")
        assertEquals("PR", result.payRunId)

        val first = server.takeRequest(1, TimeUnit.SECONDS)
        val second = server.takeRequest(1, TimeUnit.SECONDS)
        assertNotNull(first)
        assertNotNull(second)
        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
    }
}

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ContextConfiguration(initializers = [OrchestratorClientCircuitBreakerIT.Companion.Initializer::class])
@TestInstance(Lifecycle.PER_CLASS)
class OrchestratorClientCircuitBreakerIT {

    companion object {
        lateinit var server: MockWebServer

        class Initializer : ApplicationContextInitializer<ConfigurableApplicationContext> {
            override fun initialize(context: ConfigurableApplicationContext) {
                server = MockWebServer().apply {
                    dispatcher = object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().setResponseCode(500).setBody("{\"error\":\"boom\"}")
                    }
                }
                server.start()

                val baseUrl = server.url("").toString().removeSuffix("/")

                TestPropertyValues.of(
                    "downstreams.orchestrator.base-url=$baseUrl",
                    "downstreams.orchestrator.max-retries=0",
                    "downstreams.orchestrator.retry-initial-backoff=0ms",
                    "downstreams.orchestrator.retry-max-backoff=0ms",
                    "downstreams.orchestrator.circuit-breaker-enabled=true",
                    "downstreams.orchestrator.circuit-breaker.failure-threshold=1",
                    "downstreams.orchestrator.circuit-breaker.open-duration=1h",
                    "downstreams.orchestrator.circuit-breaker.half-open-max-calls=1",
                ).applyTo(context.environment)
            }
        }
    }

    @Autowired
    lateinit var client: OrchestratorClient

    @AfterAll
    fun shutdown() {
        server.shutdown()
    }

    @Test
    fun `opens circuit after failures and fails fast`() {
        assertFailsWith<Exception> {
            client.getStatus(employerId = com.example.uspayroll.shared.EmployerId("EMP"), payRunId = "PR")
        }

        assertFailsWith<CircuitBreakerOpenException> {
            client.getStatus(employerId = com.example.uspayroll.shared.EmployerId("EMP"), payRunId = "PR")
        }

        val first = server.takeRequest(1, TimeUnit.SECONDS)
        assertNotNull(first)
        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
    }
}
