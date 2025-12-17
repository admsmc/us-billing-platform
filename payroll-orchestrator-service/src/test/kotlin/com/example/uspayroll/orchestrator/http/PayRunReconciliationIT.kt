package com.example.uspayroll.orchestrator.http

import com.example.uspayroll.orchestrator.support.StubClientsTestConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
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
import java.sql.Timestamp
import java.time.Instant

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(StubClientsTestConfig::class)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@TestPropertySource(
    properties = [
        "orchestrator.internal-auth.shared-secret=dev-internal-token",
        "orchestrator.jobs.rabbit.enabled=true",
        // Keep the scheduled finalizer off for these tests.
        "orchestrator.payrun.finalizer.enabled=false",
        // No Kafka needed.
        "orchestrator.outbox.relay.enabled=false",
        "orchestrator.outbox.rabbit.relay.enabled=false",
    ],
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class PayRunReconciliationIT(
    private val rest: TestRestTemplate,
    private val jdbcTemplate: JdbcTemplate,
    private val paycheckComputationService: com.example.uspayroll.orchestrator.payrun.PaycheckComputationService,
) {

    private fun internalHeaders(): HttpHeaders = HttpHeaders().apply { set("X-Internal-Token", "dev-internal-token") }

    private fun completeItem(employerId: String, payRunId: String, employeeId: String, payPeriodId: String = "pp-1") {
        val paycheckId = jdbcTemplate.queryForObject(
            """
            SELECT paycheck_id
            FROM pay_run_item
            WHERE employer_id = ? AND pay_run_id = ? AND employee_id = ?
            """.trimIndent(),
            String::class.java,
            employerId,
            payRunId,
            employeeId,
        )!!

        val computation = paycheckComputationService.computePaycheckComputationForEmployee(
            employerId = com.example.uspayroll.shared.EmployerId(employerId),
            payRunId = payRunId,
            payPeriodId = payPeriodId,
            runType = com.example.uspayroll.orchestrator.payrun.model.PayRunType.REGULAR,
            paycheckId = paycheckId,
            employeeId = com.example.uspayroll.shared.EmployeeId(employeeId),
        )

        rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/internal/$payRunId/items/$employeeId/complete"))
                .headers(internalHeaders())
                .body(
                    PayRunController.CompleteEmployeeItemRequest(
                        paycheckId = paycheckId,
                        paycheck = computation.paycheck,
                        audit = computation.audit,
                        error = null,
                    ),
                ),
            Map::class.java,
        )
    }

    @Test
    fun `requeue queued is idempotent and does not duplicate outbox jobs`() {
        val employerId = "emp-1"
        val payRunId = "run-reconcile-1"

        val start = rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/finalize"))
                .body(
                    PayRunController.StartFinalizeRequest(
                        payPeriodId = "pp-1",
                        employeeIds = listOf("e-1", "e-2"),
                        requestedPayRunId = payRunId,
                    ),
                ),
            PayRunController.StartFinalizeResponse::class.java,
        )
        assertEquals(HttpStatus.ACCEPTED, start.statusCode)

        val initialOutbox = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM outbox_event
            WHERE destination_type = 'RABBIT' AND event_type = 'FinalizePayRunEmployeeJob'
            """.trimIndent(),
            Long::class.java,
        ) ?: 0L
        assertEquals(2L, initialOutbox)

        val headers = internalHeaders()

        // Requeue queued items - should not create duplicates.
        val r1 = rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/internal/$payRunId/reconcile/requeue-queued"))
                .headers(headers)
                .build(),
            Map::class.java,
        )
        assertEquals(HttpStatus.OK, r1.statusCode)

        val after1 = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM outbox_event
            WHERE destination_type = 'RABBIT' AND event_type = 'FinalizePayRunEmployeeJob'
            """.trimIndent(),
            Long::class.java,
        ) ?: 0L
        assertEquals(2L, after1)

        val r2 = rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/internal/$payRunId/reconcile/requeue-queued"))
                .headers(headers)
                .build(),
            Map::class.java,
        )
        assertEquals(HttpStatus.OK, r2.statusCode)

        val after2 = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM outbox_event
            WHERE destination_type = 'RABBIT' AND event_type = 'FinalizePayRunEmployeeJob'
            """.trimIndent(),
            Long::class.java,
        ) ?: 0L
        assertEquals(2L, after2)
    }

    @Test
    fun `requeue stale running moves item back to queued and enqueues job`() {
        val employerId = "emp-1"
        val payRunId = "run-reconcile-2"

        rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/finalize"))
                .body(
                    PayRunController.StartFinalizeRequest(
                        payPeriodId = "pp-1",
                        employeeIds = listOf("e-1"),
                        requestedPayRunId = payRunId,
                    ),
                ),
            PayRunController.StartFinalizeResponse::class.java,
        )

        // Simulate a stuck RUNNING item by forcing status and old updated_at.
        jdbcTemplate.update(
            """
            UPDATE pay_run_item
            SET status = 'RUNNING', updated_at = ?
            WHERE employer_id = ? AND pay_run_id = ? AND employee_id = ?
            """.trimIndent(),
            Timestamp.from(Instant.parse("2025-01-01T00:00:00Z")),
            employerId,
            payRunId,
            "e-1",
        )

        // Clear the existing outbox job to prove reconcile will re-enqueue it.
        jdbcTemplate.update(
            """
            DELETE FROM outbox_event
            WHERE destination_type = 'RABBIT' AND event_type = 'FinalizePayRunEmployeeJob'
            """.trimIndent(),
        )

        val headers = internalHeaders()
        val r = rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/internal/$payRunId/reconcile/requeue-stale-running?staleMillis=1000"))
                .headers(headers)
                .build(),
            Map::class.java,
        )
        assertEquals(HttpStatus.OK, r.statusCode)

        val queuedCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM pay_run_item
            WHERE employer_id = ? AND pay_run_id = ? AND status = 'QUEUED'
            """.trimIndent(),
            Long::class.java,
            employerId,
            payRunId,
        ) ?: 0L
        assertTrue(queuedCount >= 1L)

        val outboxCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM outbox_event
            WHERE destination_type = 'RABBIT' AND event_type = 'FinalizePayRunEmployeeJob'
            """.trimIndent(),
            Long::class.java,
        ) ?: 0L
        assertEquals(1L, outboxCount)
    }

    @Test
    fun `payment request re-enqueue is idempotent`() {
        val employerId = "emp-1"
        val payRunId = "run-reconcile-3"

        rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/finalize"))
                .body(
                    PayRunController.StartFinalizeRequest(
                        payPeriodId = "pp-1",
                        employeeIds = listOf("e-1"),
                        requestedPayRunId = payRunId,
                    ),
                ),
            PayRunController.StartFinalizeResponse::class.java,
        )

        val paycheckId = jdbcTemplate.queryForObject(
            """
            SELECT paycheck_id
            FROM pay_run_item
            WHERE employer_id = ? AND pay_run_id = ? AND employee_id = ?
            """.trimIndent(),
            String::class.java,
            employerId,
            payRunId,
            "e-1",
        )

        val computation = paycheckComputationService.computePaycheckComputationForEmployee(
            employerId = com.example.uspayroll.shared.EmployerId(employerId),
            payRunId = payRunId,
            payPeriodId = "pp-1",
            runType = com.example.uspayroll.orchestrator.payrun.model.PayRunType.REGULAR,
            paycheckId = paycheckId!!,
            employeeId = com.example.uspayroll.shared.EmployeeId("e-1"),
        )

        val headers = internalHeaders()
        rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/internal/$payRunId/items/e-1/complete"))
                .headers(headers)
                .body(
                    PayRunController.CompleteEmployeeItemRequest(
                        paycheckId = paycheckId,
                        paycheck = computation.paycheck,
                        audit = computation.audit,
                        error = null,
                    ),
                ),
            Map::class.java,
        )

        rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/$payRunId/approve"))
                .build(),
            Map::class.java,
        )

        // Make the run finalized so the reconcile endpoint is allowed.
        jdbcTemplate.update(
            "UPDATE pay_run SET status = 'FINALIZED' WHERE employer_id = ? AND pay_run_id = ?",
            employerId,
            payRunId,
        )

        val r1 = rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/internal/$payRunId/reconcile/payments/re-enqueue-requests"))
                .headers(headers)
                .build(),
            Map::class.java,
        )
        assertEquals(HttpStatus.OK, r1.statusCode)

        val count1 = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM outbox_event
            WHERE topic = 'paycheck.payment.requested' AND event_type = 'PaycheckPaymentRequested'
            """.trimIndent(),
            Long::class.java,
        ) ?: 0L
        assertEquals(1L, count1)

        val r2 = rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/internal/$payRunId/reconcile/payments/re-enqueue-requests"))
                .headers(headers)
                .build(),
            Map::class.java,
        )
        assertEquals(HttpStatus.OK, r2.statusCode)

        val count2 = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM outbox_event
            WHERE topic = 'paycheck.payment.requested' AND event_type = 'PaycheckPaymentRequested'
            """.trimIndent(),
            Long::class.java,
        ) ?: 0L
        assertEquals(1L, count2)
    }

    @Test
    fun `recompute run status sets pay_run status but does not emit kafka outbox events`() {
        val employerId = "emp-1"
        val payRunId = "run-reconcile-recompute-status-1"

        rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/finalize"))
                .body(
                    PayRunController.StartFinalizeRequest(
                        payPeriodId = "pp-1",
                        employeeIds = listOf("e-1", "e-2"),
                        requestedPayRunId = payRunId,
                    ),
                ),
            PayRunController.StartFinalizeResponse::class.java,
        )

        val headers = internalHeaders()
        completeItem(employerId, payRunId, "e-1")
        completeItem(employerId, payRunId, "e-2")

        // Force drift: mark pay_run as RUNNING.
        jdbcTemplate.update(
            "UPDATE pay_run SET status = 'RUNNING' WHERE employer_id = ? AND pay_run_id = ?",
            employerId,
            payRunId,
        )

        val r = rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/internal/$payRunId/reconcile/recompute-run-status?persist=true"))
                .headers(headers)
                .build(),
            Map::class.java,
        )
        assertEquals(HttpStatus.OK, r.statusCode)

        val statusAfter = jdbcTemplate.queryForObject(
            "SELECT status FROM pay_run WHERE employer_id = ? AND pay_run_id = ?",
            String::class.java,
            employerId,
            payRunId,
        )
        assertEquals("FINALIZED", statusAfter)

        // Must NOT emit PayRunFinalized/PaycheckFinalized outbox events.
        val payRunEventCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM outbox_event
            WHERE destination_type = 'KAFKA' AND event_type IN ('PayRunFinalized','PaycheckFinalized')
            """.trimIndent(),
            Long::class.java,
        ) ?: 0L
        assertEquals(0L, payRunEventCount)
    }

    @Test
    fun `recompute run status persist=false only computes and does not update pay_run`() {
        val employerId = "emp-1"
        val payRunId = "run-reconcile-recompute-status-2"

        rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/finalize"))
                .body(
                    PayRunController.StartFinalizeRequest(
                        payPeriodId = "pp-1",
                        employeeIds = listOf("e-1"),
                        requestedPayRunId = payRunId,
                    ),
                ),
            PayRunController.StartFinalizeResponse::class.java,
        )

        val headers = internalHeaders()
        completeItem(employerId, payRunId, "e-1")

        // Force drift: leave pay_run as RUNNING.
        jdbcTemplate.update(
            "UPDATE pay_run SET status = 'RUNNING' WHERE employer_id = ? AND pay_run_id = ?",
            employerId,
            payRunId,
        )

        val r = rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/internal/$payRunId/reconcile/recompute-run-status?persist=false"))
                .headers(headers)
                .build(),
            Map::class.java,
        )
        assertEquals(HttpStatus.OK, r.statusCode)

        // Still RUNNING in DB (dry-run).
        val statusAfter = jdbcTemplate.queryForObject(
            "SELECT status FROM pay_run WHERE employer_id = ? AND pay_run_id = ?",
            String::class.java,
            employerId,
            payRunId,
        )
        assertEquals("RUNNING", statusAfter)

        // But response should indicate the computed status.
        val computed = r.body?.get("computedStatus") as String?
        assertEquals("FINALIZED", computed)
    }

    @Test
    fun `finalize and enqueue events emits deterministic kafka outbox events`() {
        val employerId = "emp-1"
        val payRunId = "run-reconcile-finalize-1"

        rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/finalize"))
                .body(
                    PayRunController.StartFinalizeRequest(
                        payPeriodId = "pp-1",
                        employeeIds = listOf("e-1", "e-2"),
                        requestedPayRunId = payRunId,
                    ),
                ),
            PayRunController.StartFinalizeResponse::class.java,
        )

        val headers = internalHeaders()
        completeItem(employerId, payRunId, "e-1")
        completeItem(employerId, payRunId, "e-2")

        val r1 = rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/internal/$payRunId/reconcile/finalize-and-enqueue-events"))
                .headers(headers)
                .build(),
            Map::class.java,
        )
        assertEquals(HttpStatus.OK, r1.statusCode)

        val payRunEventCount1 = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM outbox_event
            WHERE destination_type = 'KAFKA' AND event_type = 'PayRunFinalized'
            """.trimIndent(),
            Long::class.java,
        ) ?: 0L
        assertEquals(1L, payRunEventCount1)

        val paycheckEventCount1 = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM outbox_event
            WHERE destination_type = 'KAFKA' AND event_type = 'PaycheckFinalized'
            """.trimIndent(),
            Long::class.java,
        ) ?: 0L
        assertEquals(2L, paycheckEventCount1)

        // Call again - should not duplicate due to deterministic event ids.
        val r2 = rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/internal/$payRunId/reconcile/finalize-and-enqueue-events"))
                .headers(headers)
                .build(),
            Map::class.java,
        )
        assertEquals(HttpStatus.OK, r2.statusCode)

        val payRunEventCount2 = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM outbox_event
            WHERE destination_type = 'KAFKA' AND event_type = 'PayRunFinalized'
            """.trimIndent(),
            Long::class.java,
        ) ?: 0L
        assertEquals(1L, payRunEventCount2)

        val paycheckEventCount2 = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM outbox_event
            WHERE destination_type = 'KAFKA' AND event_type = 'PaycheckFinalized'
            """.trimIndent(),
            Long::class.java,
        ) ?: 0L
        assertEquals(2L, paycheckEventCount2)
    }

    @Test
    fun `recompute payment status sets pay_run payment_status from paycheck counts`() {
        val employerId = "emp-1"
        val payRunId = "run-reconcile-payment-status-1"

        rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/finalize"))
                .body(
                    PayRunController.StartFinalizeRequest(
                        payPeriodId = "pp-1",
                        employeeIds = listOf("e-1", "e-2"),
                        requestedPayRunId = payRunId,
                    ),
                ),
            PayRunController.StartFinalizeResponse::class.java,
        )

        val headers = internalHeaders()
        completeItem(employerId, payRunId, "e-1")
        completeItem(employerId, payRunId, "e-2")

        val ids = jdbcTemplate.query(
            """
            SELECT paycheck_id
            FROM paycheck
            WHERE employer_id = ? AND pay_run_id = ?
            ORDER BY paycheck_id
            """.trimIndent(),
            { rs, _ -> rs.getString("paycheck_id") },
            employerId,
            payRunId,
        )
        assertEquals(2, ids.size)

        // Force one paid and one failed.
        jdbcTemplate.update(
            "UPDATE paycheck SET payment_status = 'PAID' WHERE employer_id = ? AND paycheck_id = ?",
            employerId,
            ids[0],
        )
        jdbcTemplate.update(
            "UPDATE paycheck SET payment_status = 'FAILED' WHERE employer_id = ? AND paycheck_id = ?",
            employerId,
            ids[1],
        )

        val r = rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/internal/$payRunId/reconcile/payments/recompute-run-status"))
                .headers(headers)
                .build(),
            Map::class.java,
        )
        assertEquals(HttpStatus.OK, r.statusCode)

        val runStatus = jdbcTemplate.queryForObject(
            "SELECT payment_status FROM pay_run WHERE employer_id = ? AND pay_run_id = ?",
            String::class.java,
            employerId,
            payRunId,
        )
        assertEquals("PARTIALLY_PAID", runStatus)
    }
}
