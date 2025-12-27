package com.example.usbilling.orchestrator.events

import com.example.usbilling.messaging.events.payments.PaycheckPaymentLifecycleStatus
import com.example.usbilling.messaging.events.payments.PaycheckPaymentStatusChangedEvent
import com.example.usbilling.messaging.events.reporting.PaycheckLedgerAction
import com.example.usbilling.messaging.events.reporting.PaycheckLedgerEvent
import com.example.usbilling.orchestrator.payments.PaymentStatusProjectionService
import com.example.usbilling.orchestrator.support.InternalAuthTestSupport
import com.example.usbilling.orchestrator.support.StubClientsTestConfig
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.http.RequestEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.TestConstructor
import org.springframework.test.context.TestPropertySource
import java.net.URI
import java.time.Instant
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(StubClientsTestConfig::class)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@TestPropertySource(
    properties = [
        "orchestrator.internal-auth.jwt-keys.k1=dev-internal-token",
        "orchestrator.internal-auth.jwt-default-kid=k1",
        "orchestrator.payrun.execute.enabled=true",
    ],
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class PaycheckLedgerEventsIT(
    private val rest: TestRestTemplate,
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
    private val paymentProjection: PaymentStatusProjectionService,
) {

    @BeforeEach
    fun cleanDb() {
        jdbcTemplate.update("DELETE FROM paycheck_audit")
        jdbcTemplate.update("DELETE FROM paycheck_payment")
        jdbcTemplate.update("DELETE FROM paycheck")
        jdbcTemplate.update("DELETE FROM pay_run_item")
        jdbcTemplate.update("DELETE FROM pay_run")
        jdbcTemplate.update("DELETE FROM outbox_event")
    }

    @Test
    fun `approving a regular payrun enqueues COMMITTED paycheck ledger events`() {
        // Use a class-specific employerId to avoid cross-test collisions in the shared H2 DB.
        val employerId = "emp-ledger-1"
        val payRunId = "run-ledger-commit"

        startAndExecutePayRun(employerId = employerId, payRunId = payRunId, employeeIds = listOf("e-1", "e-2"))

        val approve = rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/$payRunId/approve")).build(),
            Map::class.java,
        )
        assertEquals(HttpStatus.OK, approve.statusCode)

        // Only consider ledger events for this pay run by joining through pay_run_item.
        val rows = jdbcTemplate.query(
            """
            SELECT oe.payload_json
            FROM outbox_event oe
            JOIN pay_run_item pri
              ON oe.aggregate_id = pri.paycheck_id
            WHERE oe.event_type = 'PaycheckLedger'
              AND oe.topic = 'paycheck.ledger'
              AND pri.employer_id = ?
              AND pri.pay_run_id = ?
            ORDER BY oe.created_at
            """.trimIndent(),
            { rs, _ -> rs.getString("payload_json") },
            employerId,
            payRunId,
        )

        assertEquals(2, rows.size)

        val events = rows.map { objectMapper.readValue(it, PaycheckLedgerEvent::class.java) }
        assertEquals(setOf(PaycheckLedgerAction.COMMITTED), events.map { it.action }.toSet())
        assertEquals(setOf(payRunId), events.map { it.payRunId }.toSet())
        assertEquals(setOf("REGULAR"), events.map { it.payRunType }.toSet())
        assertEquals(setOf("pp-1"), events.map { it.payPeriodId }.toSet())
        assertEquals(setOf(employerId), events.map { it.employerId }.toSet())

        val first = events.first()
        assertNotNull(first.eventId)
        assertEquals(true, first.eventId.startsWith("paycheck-ledger:committed:$employerId:"))
    }

    @Test
    fun `approving a void payrun enqueues VOIDED paycheck ledger events with correction linkage`() {
        val employerId = "emp-ledger-1"
        val payRunId = "run-ledger-src-${UUID.randomUUID()}"

        // 1) create + finalize + approve + initiate payments
        startAndExecutePayRun(employerId = employerId, payRunId = payRunId, employeeIds = listOf("e-1"))

        rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/$payRunId/approve")).build(),
            Map::class.java,
        )

        rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/$payRunId/payments/initiate")).build(),
            Map::class.java,
        )

        val originalPaycheckId = paycheckIdForEmployee(employerId = employerId, payRunId = payRunId, employeeId = "e-1")

        // mark as PAID (simulate payments-service status changed event)
        paymentProjection.applyPaymentStatusChanged(
            PaycheckPaymentStatusChangedEvent(
                eventId = "evt-ledger-paid-${UUID.randomUUID()}",
                occurredAt = Instant.now(),
                employerId = employerId,
                payRunId = payRunId,
                paycheckId = originalPaycheckId,
                paymentId = "pmt-$originalPaycheckId",
                status = PaycheckPaymentLifecycleStatus.SETTLED,
            ),
        )

        // 2) VOID + approve
        val voidResp = rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/$payRunId/void"))
                .body(com.example.uspayroll.orchestrator.http.PayRunController.StartCorrectionRequest()),
            com.example.uspayroll.orchestrator.http.PayRunController.StartCorrectionResponse::class.java,
        )
        assertEquals(HttpStatus.ACCEPTED, voidResp.statusCode)
        val voidPayRunId = voidResp.body!!.correctionPayRunId

        val voidPaycheckId = paycheckIdForEmployee(employerId = employerId, payRunId = voidPayRunId, employeeId = "e-1")

        val approveVoid = rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/$voidPayRunId/approve")).build(),
            Map::class.java,
        )
        assertEquals(HttpStatus.OK, approveVoid.statusCode)

        val payload = jdbcTemplate.queryForObject(
            """
            SELECT payload_json
            FROM outbox_event
            WHERE event_type = 'PaycheckLedger' AND aggregate_id = ?
            ORDER BY created_at DESC
            LIMIT 1
            """.trimIndent(),
            String::class.java,
            voidPaycheckId,
        )
        assertNotNull(payload)

        val evt = objectMapper.readValue(payload, PaycheckLedgerEvent::class.java)
        assertEquals(PaycheckLedgerAction.VOIDED, evt.action)
        assertEquals(voidPayRunId, evt.payRunId)
        assertEquals(voidPaycheckId, evt.paycheckId)
        assertEquals(originalPaycheckId, evt.correctionOfPaycheckId)
        assertEquals(payRunId, evt.correctionOfPayRunId)
    }

    private fun startAndExecutePayRun(employerId: String, payRunId: String, employeeIds: List<String>) {
        val start = rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/finalize"))
                .body(
                    com.example.uspayroll.orchestrator.http.PayRunController.StartFinalizeRequest(
                        payPeriodId = "pp-1",
                        employeeIds = employeeIds,
                        requestedPayRunId = payRunId,
                    ),
                ),
            com.example.uspayroll.orchestrator.http.PayRunController.StartFinalizeResponse::class.java,
        )
        assertEquals(HttpStatus.ACCEPTED, start.statusCode)

        val execHeaders = InternalAuthTestSupport.internalAuthHeaders()
        val exec = rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/internal/$payRunId/execute?batchSize=10"))
                .headers(execHeaders)
                .build(),
            Map::class.java,
        )
        assertEquals(HttpStatus.OK, exec.statusCode)

        waitForTerminalFinalize(employerId = employerId, payRunId = payRunId)
    }

    private fun waitForTerminalFinalize(employerId: String, payRunId: String) {
        val deadlineMs = System.currentTimeMillis() + 120_000
        while (System.currentTimeMillis() < deadlineMs) {
            val status = jdbcTemplate.query(
                "SELECT status FROM pay_run WHERE employer_id = ? AND pay_run_id = ?",
                { rs, _ -> rs.getString("status") },
                employerId,
                payRunId,
            ).firstOrNull()

            if (status == "FINALIZED" || status == "PARTIALLY_FINALIZED" || status == "FAILED") {
                return
            }
            Thread.sleep(100)
        }

        throw AssertionError("Timed out waiting for terminal pay_run.status")
    }

    private fun paycheckIdForEmployee(employerId: String, payRunId: String, employeeId: String): String {
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
        )
        assertNotNull(paycheckId)
        return paycheckId!!
    }
}

// end
