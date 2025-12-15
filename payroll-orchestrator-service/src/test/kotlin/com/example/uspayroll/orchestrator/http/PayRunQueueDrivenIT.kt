package com.example.uspayroll.orchestrator.http

import com.example.uspayroll.orchestrator.support.StubClientsTestConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.RequestEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.TestConstructor
import org.springframework.test.context.TestPropertySource
import java.net.URI

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(StubClientsTestConfig::class)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@TestPropertySource(
    properties = [
        "orchestrator.internal-auth.shared-secret=dev-internal-token",
        "orchestrator.jobs.rabbit.enabled=true",
        // Keep the finalizer off for these tests (we test per-item behavior + enqueueing here).
        "orchestrator.payrun.finalizer.enabled=false",
    ],
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class PayRunQueueDrivenIT(
    private val rest: TestRestTemplate,
    private val jdbcTemplate: JdbcTemplate,
) {

    @Test
    fun `start finalize enqueues one Rabbit outbox job per employee`() {
        val employerId = "emp-1"

        val start = rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/finalize"))
                .body(
                    PayRunController.StartFinalizeRequest(
                        payPeriodId = "pp-1",
                        employeeIds = listOf("e-1", "e-2"),
                        requestedPayRunId = "run-queue-1",
                    ),
                ),
            PayRunController.StartFinalizeResponse::class.java,
        )

        assertEquals(HttpStatus.ACCEPTED, start.statusCode)
        assertEquals("run-queue-1", start.body!!.payRunId)

        val rabbitOutbox = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM outbox_event
            WHERE destination_type = 'RABBIT' AND event_type = 'FinalizePayRunEmployeeJob'
            """.trimIndent(),
            Long::class.java,
        ) ?: 0L

        assertEquals(2L, rabbitOutbox)

        val exchange = jdbcTemplate.queryForObject(
            """
            SELECT topic
            FROM outbox_event
            WHERE destination_type = 'RABBIT'
            LIMIT 1
            """.trimIndent(),
            String::class.java,
        )
        assertEquals("payrun.jobs", exchange)

        val routingKey = jdbcTemplate.queryForObject(
            """
            SELECT event_key
            FROM outbox_event
            WHERE destination_type = 'RABBIT'
            LIMIT 1
            """.trimIndent(),
            String::class.java,
        )
        assertEquals("payrun.finalize.employee", routingKey)
    }

    @Test
    fun `start finalize is idempotent via Idempotency-Key header`() {
        val employerId = "emp-1"

        val idempotencyKey = "idem-queue-1"

        val start1 = rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/finalize"))
                .header("Idempotency-Key", idempotencyKey)
                .body(
                    PayRunController.StartFinalizeRequest(
                        payPeriodId = "pp-1",
                        employeeIds = listOf("e-1", "e-2"),
                        requestedPayRunId = "run-queue-idem-1",
                    ),
                ),
            PayRunController.StartFinalizeResponse::class.java,
        )

        assertEquals(HttpStatus.ACCEPTED, start1.statusCode)
        val payRunId = start1.body!!.payRunId

        val start2 = rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/finalize"))
                .header("Idempotency-Key", idempotencyKey)
                .body(
                    PayRunController.StartFinalizeRequest(
                        payPeriodId = "pp-1",
                        employeeIds = listOf("e-1", "e-2"),
                        // Even if the client retries with a different requested id, the idempotency key wins.
                        requestedPayRunId = "run-queue-idem-2",
                    ),
                ),
            PayRunController.StartFinalizeResponse::class.java,
        )

        assertEquals(HttpStatus.ACCEPTED, start2.statusCode)
        assertEquals(payRunId, start2.body!!.payRunId)
        assertEquals(false, start2.body!!.created)

        val rabbitOutbox = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM outbox_event
            WHERE destination_type = 'RABBIT' AND event_type = 'FinalizePayRunEmployeeJob'
            """.trimIndent(),
            Long::class.java,
        ) ?: 0L

        // Still 2 total jobs (not doubled by the retry).
        assertEquals(2L, rabbitOutbox)
    }

    @Test
    fun `internal finalize item endpoint computes paycheck idempotently`() {
        val employerId = "emp-1"

        rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/finalize"))
                .body(
                    PayRunController.StartFinalizeRequest(
                        payPeriodId = "pp-1",
                        employeeIds = listOf("e-1"),
                        requestedPayRunId = "run-queue-2",
                    ),
                ),
            PayRunController.StartFinalizeResponse::class.java,
        )

        val headers = HttpHeaders().apply { set("X-Internal-Token", "dev-internal-token") }
        val r1 = rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/internal/run-queue-2/items/e-1/finalize"))
                .headers(headers)
                .build(),
            Map::class.java,
        )

        assertEquals(HttpStatus.OK, r1.statusCode)
        assertEquals("SUCCEEDED", r1.body!!["itemStatus"])
        assertEquals(false, r1.body!!["retryable"])
        val paycheckId = r1.body!!["paycheckId"] as String?
        assertNotNull(paycheckId)

        // Call again - should be a no-op (still succeeded).
        val r2 = rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/internal/run-queue-2/items/e-1/finalize"))
                .headers(headers)
                .build(),
            Map::class.java,
        )

        assertEquals(HttpStatus.OK, r2.statusCode)
        assertEquals("SUCCEEDED", r2.body!!["itemStatus"])
        assertEquals(false, r2.body!!["retryable"])

        val paycheckCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM paycheck
            WHERE employer_id = ? AND paycheck_id = ?
            """.trimIndent(),
            Long::class.java,
            employerId,
            paycheckId,
        ) ?: 0L

        assertEquals(1L, paycheckCount)
    }

    @Test
    fun `failed computation is requeued as retryable by default`() {
        val employerId = "emp-1"

        rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/finalize"))
                .body(
                    PayRunController.StartFinalizeRequest(
                        payPeriodId = "pp-1",
                        employeeIds = listOf("e-bad"),
                        requestedPayRunId = "run-queue-3",
                    ),
                ),
            PayRunController.StartFinalizeResponse::class.java,
        )

        val headers = HttpHeaders().apply { set("X-Internal-Token", "dev-internal-token") }
        val r1 = rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/internal/run-queue-3/items/e-bad/finalize"))
                .headers(headers)
                .build(),
            Map::class.java,
        )

        assertEquals(HttpStatus.OK, r1.statusCode)
        assertEquals("QUEUED", r1.body!!["itemStatus"])
        assertEquals(true, r1.body!!["retryable"])
    }
}
