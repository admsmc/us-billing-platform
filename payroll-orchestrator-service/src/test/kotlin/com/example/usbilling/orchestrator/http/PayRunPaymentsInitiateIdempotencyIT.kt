package com.example.usbilling.orchestrator.http

import com.example.usbilling.orchestrator.support.InternalAuthTestSupport
import com.example.usbilling.orchestrator.support.StubClientsTestConfig
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(StubClientsTestConfig::class)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@TestPropertySource(
    properties = [
        "orchestrator.internal-auth.jwt-keys.k1=dev-internal-token",
        "orchestrator.internal-auth.jwt-default-kid=k1",
        "orchestrator.jobs.rabbit.enabled=true",
        // Keep the finalizer off; the test drives per-item finalization via the internal endpoint.
        "orchestrator.payrun.finalizer.enabled=false",
    ],
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class PayRunPaymentsInitiateIdempotencyIT(
    private val rest: TestRestTemplate,
    private val jdbcTemplate: JdbcTemplate,
    private val paycheckComputationService: com.example.uspayroll.orchestrator.payrun.PaycheckComputationService,
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
    fun `payments initiation is idempotent via Idempotency-Key header`() {
        // Use a class-specific employerId to avoid cross-test collisions on business keys.
        val employerId = "emp-payments-idem"
        val payRunId = "run-payments-idem-1"

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

        // Complete both items (internal endpoint).
        val internalHeaders = InternalAuthTestSupport.internalAuthHeaders()

        fun complete(employeeId: String) {
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
                payPeriodId = "pp-1",
                runType = com.example.uspayroll.orchestrator.payrun.model.PayRunType.REGULAR,
                paycheckId = paycheckId,
                employeeId = com.example.uspayroll.shared.EmployeeId(employeeId),
            )

            rest.exchange(
                RequestEntity.post(URI.create("/employers/$employerId/payruns/internal/$payRunId/items/$employeeId/complete"))
                    .headers(internalHeaders)
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

        complete("e-1")
        complete("e-2")

        // Approve.
        rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/$payRunId/approve"))
                .build(),
            Map::class.java,
        )

        val idemKey = "idem-payments-1"

        val r1 = rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/$payRunId/payments/initiate"))
                .header("Idempotency-Key", idemKey)
                .build(),
            PayRunController.InitiatePaymentsResponse::class.java,
        )
        assertEquals(HttpStatus.OK, r1.statusCode)
        assertNotNull(r1.headers.getFirst("Idempotency-Key"))
        assertEquals(idemKey, r1.headers.getFirst("Idempotency-Key"))

        val r2 = rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/$payRunId/payments/initiate"))
                .header("Idempotency-Key", idemKey)
                .build(),
            PayRunController.InitiatePaymentsResponse::class.java,
        )
        assertEquals(HttpStatus.OK, r2.statusCode)
        assertEquals(idemKey, r2.headers.getFirst("Idempotency-Key"))

        // Ensure we did not double-enqueue outbox payment request events.
        val outboxCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM outbox_event
            WHERE topic = 'paycheck.payment.requested' AND event_type = 'PaycheckPaymentRequested'
            """.trimIndent(),
            Long::class.java,
        ) ?: 0L

        // Two employees -> two payment requests total (no duplicates on retry).
        assertEquals(2L, outboxCount)
    }

    @Test
    fun `payments initiation idempotency key cannot be reused across payruns`() {
        val employerId = "emp-payments-idem"

        fun setupAndApprove(payRunId: String, employeeId: String, runSequence: Int) {
            rest.exchange(
                RequestEntity.post(URI.create("/employers/$employerId/payruns/finalize"))
                    .body(
                        PayRunController.StartFinalizeRequest(
                            payPeriodId = "pp-1",
                            employeeIds = listOf(employeeId),
                            requestedPayRunId = payRunId,
                            runSequence = runSequence,
                        ),
                    ),
                PayRunController.StartFinalizeResponse::class.java,
            )

            val internalHeaders = InternalAuthTestSupport.internalAuthHeaders()

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
                payPeriodId = "pp-1",
                runType = com.example.uspayroll.orchestrator.payrun.model.PayRunType.REGULAR,
                paycheckId = paycheckId,
                employeeId = com.example.uspayroll.shared.EmployeeId(employeeId),
            )

            rest.exchange(
                RequestEntity.post(URI.create("/employers/$employerId/payruns/internal/$payRunId/items/$employeeId/complete"))
                    .headers(internalHeaders)
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
        }

        val idemKey = "idem-payments-shared"

        setupAndApprove(payRunId = "run-payments-idem-a", employeeId = "e-1", runSequence = 1)
        val ok = rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/run-payments-idem-a/payments/initiate"))
                .header("Idempotency-Key", idemKey)
                .build(),
            Map::class.java,
        )
        assertEquals(HttpStatus.OK, ok.statusCode)

        setupAndApprove(payRunId = "run-payments-idem-b", employeeId = "e-2", runSequence = 2)
        val conflict = rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/run-payments-idem-b/payments/initiate"))
                .header("Idempotency-Key", idemKey)
                .build(),
            String::class.java,
        )
        assertEquals(HttpStatus.CONFLICT, conflict.statusCode)
    }
}
