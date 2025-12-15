package com.example.uspayroll.orchestrator.payments

import com.example.uspayroll.messaging.events.payments.PaycheckPaymentLifecycleStatus
import com.example.uspayroll.messaging.events.payments.PaycheckPaymentStatusChangedEvent
import com.example.uspayroll.orchestrator.http.PayRunController
import com.example.uspayroll.orchestrator.payrun.model.PaymentStatus
import com.example.uspayroll.orchestrator.support.StubClientsTestConfig
import org.junit.jupiter.api.Assertions.assertEquals
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
import java.time.Instant
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(StubClientsTestConfig::class)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@TestPropertySource(
    properties = [
        "orchestrator.internal-auth.shared-secret=dev-internal-token",
        "orchestrator.payrun.execute.enabled=true",
    ],
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class PaymentStatusProjectionIT(
    private val rest: TestRestTemplate,
    private val jdbcTemplate: JdbcTemplate,
    private val projectionService: PaymentStatusProjectionService,
) {
    @Test
    fun `settled payment event marks paycheck and payrun paid`() {
        val employerId = "emp-1"
        val payRunId = "run-proj-${UUID.randomUUID()}"
        // Avoid business-key collisions across concurrently-running Spring contexts.
        val runSequence = ((UUID.randomUUID().mostSignificantBits ushr 1) % 1_000_000L).toInt() + 1

        rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/finalize"))
                .body(
                    PayRunController.StartFinalizeRequest(
                        payPeriodId = "pp-1",
                        employeeIds = listOf("e-1"),
                        runSequence = runSequence,
                        requestedPayRunId = payRunId,
                    ),
                ),
            PayRunController.StartFinalizeResponse::class.java,
        )

        val execHeaders = HttpHeaders().apply { set("X-Internal-Token", "dev-internal-token") }
        rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/internal/$payRunId/execute?batchSize=10"))
                .headers(execHeaders)
                .build(),
            Map::class.java,
        )

        waitForTerminalFinalize(employerId, payRunId)

        val approve = rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/$payRunId/approve"))
                .build(),
            Map::class.java,
        )
        assertEquals(HttpStatus.OK, approve.statusCode)

        val initiate = rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/$payRunId/payments/initiate"))
                .build(),
            Map::class.java,
        )
        assertEquals(HttpStatus.OK, initiate.statusCode)

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
        )!!

        // Apply event as if emitted by payments-service.
        projectionService.applyPaymentStatusChanged(
            PaycheckPaymentStatusChangedEvent(
                eventId = "paycheck-payment-status-changed:$employerId:$paycheckId:SETTLED",
                occurredAt = Instant.now(),
                employerId = employerId,
                payRunId = payRunId,
                paycheckId = paycheckId,
                paymentId = "pmt-$paycheckId",
                status = PaycheckPaymentLifecycleStatus.SETTLED,
            ),
        )

        val paycheckStatus = jdbcTemplate.queryForObject(
            "SELECT payment_status FROM paycheck WHERE employer_id = ? AND paycheck_id = ?",
            String::class.java,
            employerId,
            paycheckId,
        )
        assertEquals(PaymentStatus.PAID.name, paycheckStatus)

        val projectionStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM paycheck_payment WHERE employer_id = ? AND paycheck_id = ?",
            String::class.java,
            employerId,
            paycheckId,
        )
        assertEquals("SETTLED", projectionStatus)

        val payRunStatus = jdbcTemplate.queryForObject(
            "SELECT payment_status FROM pay_run WHERE employer_id = ? AND pay_run_id = ?",
            String::class.java,
            employerId,
            payRunId,
        )
        assertEquals(PaymentStatus.PAID.name, payRunStatus)
    }

    @Test
    fun `mixed settled and failed events mark payrun partially paid`() {
        val employerId = "emp-1"
        val payRunId = "run-proj-${UUID.randomUUID()}"
        // Avoid business-key collisions across concurrently-running Spring contexts.
        val runSequence = ((UUID.randomUUID().mostSignificantBits ushr 1) % 1_000_000L).toInt() + 1

        rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/finalize"))
                .body(
                    PayRunController.StartFinalizeRequest(
                        payPeriodId = "pp-1",
                        employeeIds = listOf("e-1", "e-2"),
                        runSequence = runSequence,
                        requestedPayRunId = payRunId,
                    ),
                ),
            PayRunController.StartFinalizeResponse::class.java,
        )

        val execHeaders = HttpHeaders().apply { set("X-Internal-Token", "dev-internal-token") }
        rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/internal/$payRunId/execute?batchSize=10"))
                .headers(execHeaders)
                .build(),
            Map::class.java,
        )

        waitForTerminalFinalize(employerId, payRunId)

        rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/$payRunId/approve"))
                .build(),
            Map::class.java,
        )

        rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/$payRunId/payments/initiate"))
                .build(),
            Map::class.java,
        )

        val paycheck1 = jdbcTemplate.queryForObject(
            """
            SELECT paycheck_id FROM pay_run_item
            WHERE employer_id = ? AND pay_run_id = ? AND employee_id = ?
            """.trimIndent(),
            String::class.java,
            employerId,
            payRunId,
            "e-1",
        )!!

        val paycheck2 = jdbcTemplate.queryForObject(
            """
            SELECT paycheck_id FROM pay_run_item
            WHERE employer_id = ? AND pay_run_id = ? AND employee_id = ?
            """.trimIndent(),
            String::class.java,
            employerId,
            payRunId,
            "e-2",
        )!!

        projectionService.applyPaymentStatusChanged(
            PaycheckPaymentStatusChangedEvent(
                eventId = "paycheck-payment-status-changed:$employerId:$paycheck1:SETTLED",
                occurredAt = Instant.now(),
                employerId = employerId,
                payRunId = payRunId,
                paycheckId = paycheck1,
                paymentId = "pmt-$paycheck1",
                status = PaycheckPaymentLifecycleStatus.SETTLED,
            ),
        )

        projectionService.applyPaymentStatusChanged(
            PaycheckPaymentStatusChangedEvent(
                eventId = "paycheck-payment-status-changed:$employerId:$paycheck2:FAILED",
                occurredAt = Instant.now(),
                employerId = employerId,
                payRunId = payRunId,
                paycheckId = paycheck2,
                paymentId = "pmt-$paycheck2",
                status = PaycheckPaymentLifecycleStatus.FAILED,
            ),
        )

        val payRunStatus = jdbcTemplate.queryForObject(
            "SELECT payment_status FROM pay_run WHERE employer_id = ? AND pay_run_id = ?",
            String::class.java,
            employerId,
            payRunId,
        )

        assertEquals(PaymentStatus.PARTIALLY_PAID.name, payRunStatus)
    }

    private fun waitForTerminalFinalize(employerId: String, payRunId: String) {
        // When running the full multi-module build, containers/DB can be slower; keep this conservative.
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
}
