package com.example.uspayroll.orchestrator.http

import com.example.uspayroll.orchestrator.payrun.model.PayRunStatus
import com.example.uspayroll.orchestrator.support.InternalAuthTestSupport
import com.example.uspayroll.orchestrator.support.StubClientsTestConfig
import com.example.uspayroll.payroll.engine.PayrollEngine
import com.example.uspayroll.payroll.model.PaycheckResult
import com.example.uspayroll.payroll.model.audit.PaycheckAudit
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
class PayRunWorkflowIT(
    private val rest: TestRestTemplate,
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
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
    fun `start finalize returns 202 and execution finalizes`() {
        val employerId = "emp-1"

        val start = rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/finalize"))
                .body(
                    PayRunController.StartFinalizeRequest(
                        payPeriodId = "pp-1",
                        employeeIds = listOf("e-1", "e-2"),
                        requestedPayRunId = "run-it-1",
                    ),
                ),
            PayRunController.StartFinalizeResponse::class.java,
        )

        assertEquals(HttpStatus.ACCEPTED, start.statusCode)
        assertEquals("run-it-1", start.body!!.payRunId)

        val execHeaders = InternalAuthTestSupport.internalAuthHeaders()
        val exec = rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/internal/run-it-1/execute?batchSize=10"))
                .headers(execHeaders)
                .build(),
            Map::class.java,
        )
        assertEquals(HttpStatus.OK, exec.statusCode)

        val status = rest.getForEntity(
            "/employers/$employerId/payruns/run-it-1",
            PayRunController.PayRunStatusResponse::class.java,
        )
        assertEquals(HttpStatus.OK, status.statusCode)
        assertEquals(PayRunStatus.FINALIZED, status.body!!.status)
        assertEquals(2, status.body!!.counts.total)
        assertEquals(2, status.body!!.counts.succeeded)
        assertEquals(0, status.body!!.counts.failed)

        val outboxCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM outbox_event WHERE status = 'PENDING'",
            Long::class.java,
        ) ?: 0L
        assertEquals(3L, outboxCount) // 1 payrun + 2 paychecks

        val paycheckId = jdbcTemplate.queryForObject(
            """
            SELECT paycheck_id
            FROM pay_run_item
            WHERE employer_id = ? AND pay_run_id = ? AND employee_id = ?
            """.trimIndent(),
            String::class.java,
            employerId,
            "run-it-1",
            "e-1",
        )
        assertNotNull(paycheckId)

        val paycheck = rest.getForEntity(
            "/employers/$employerId/paychecks/$paycheckId",
            PaycheckResult::class.java,
        )
        assertEquals(HttpStatus.OK, paycheck.statusCode)
        assertNotNull(paycheck.body)
        assertEquals(0, paycheck.body!!.trace.steps.size)

        val auditCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM paycheck_audit
            WHERE employer_id = ? AND paycheck_id = ?
            """.trimIndent(),
            Long::class.java,
            employerId,
            paycheckId,
        ) ?: 0L
        assertEquals(1L, auditCount)

        val schemaVersion = jdbcTemplate.queryForObject(
            """
            SELECT schema_version
            FROM paycheck_audit
            WHERE employer_id = ? AND paycheck_id = ?
            """.trimIndent(),
            Int::class.java,
            employerId,
            paycheckId,
        )
        assertEquals(1, schemaVersion)

        // Internal-only audit API exposure.
        val auditResponse = rest.exchange(
            RequestEntity.get(URI.create("/employers/$employerId/paychecks/internal/$paycheckId/audit"))
                .headers(execHeaders)
                .build(),
            PaycheckAudit::class.java,
        )
        assertEquals(HttpStatus.OK, auditResponse.statusCode)
        assertNotNull(auditResponse.body)

        val fromApi = auditResponse.body!!
        assertEquals(1, fromApi.schemaVersion)
        assertEquals(PayrollEngine.version(), fromApi.engineVersion)
        assertEquals(employerId, fromApi.employerId)
        assertEquals(paycheckId, fromApi.paycheckId)
        assertEquals("run-it-1", fromApi.payRunId)
        assertEquals("pp-1", fromApi.payPeriodId)

        val auditJson = jdbcTemplate.queryForObject(
            """
            SELECT audit_json
            FROM paycheck_audit
            WHERE employer_id = ? AND paycheck_id = ?
            """.trimIndent(),
            String::class.java,
            employerId,
            paycheckId,
        )
        assertNotNull(auditJson)

        val fromDb = objectMapper.readValue(auditJson, PaycheckAudit::class.java)
        assertEquals(fromApi, fromDb)

        val auditUnauthorized = rest.exchange(
            RequestEntity.get(URI.create("/employers/$employerId/paychecks/internal/$paycheckId/audit"))
                .build(),
            Any::class.java,
        )
        assertEquals(HttpStatus.UNAUTHORIZED, auditUnauthorized.statusCode)
    }

    @Test
    fun `partial failure yields partially_finalized with per-item errors`() {
        val employerId = "emp-1"

        val start = rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/finalize"))
                .body(
                    PayRunController.StartFinalizeRequest(
                        payPeriodId = "pp-1",
                        employeeIds = listOf("e-1", "e-bad"),
                        requestedPayRunId = "run-it-2",
                    ),
                ),
            PayRunController.StartFinalizeResponse::class.java,
        )

        assertEquals(HttpStatus.ACCEPTED, start.statusCode)

        val execHeaders = InternalAuthTestSupport.internalAuthHeaders()
        rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/internal/run-it-2/execute?batchSize=10"))
                .headers(execHeaders)
                .build(),
            Map::class.java,
        )

        val status = rest.getForEntity(
            "/employers/$employerId/payruns/run-it-2?failureLimit=10",
            PayRunController.PayRunStatusResponse::class.java,
        )

        assertEquals(PayRunStatus.PARTIALLY_FINALIZED, status.body!!.status)
        assertEquals(2, status.body!!.counts.total)
        assertEquals(1, status.body!!.counts.succeeded)
        assertEquals(1, status.body!!.counts.failed)
        assertEquals(1, status.body!!.failures.size)
        assertEquals("e-bad", status.body!!.failures.first().employeeId)

        val outboxCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM outbox_event WHERE status = 'PENDING'",
            Long::class.java,
        ) ?: 0L
        assertEquals(2L, outboxCount) // 1 payrun + 1 paycheck
    }

    @Test
    fun `idempotency key returns same payrun`() {
        val employerId = "emp-1"

        val r1 = rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/finalize"))
                .body(
                    PayRunController.StartFinalizeRequest(
                        payPeriodId = "pp-1",
                        employeeIds = listOf("e-1"),
                        requestedPayRunId = "run-it-3",
                        idempotencyKey = "idem-1",
                    ),
                ),
            PayRunController.StartFinalizeResponse::class.java,
        )
        assertEquals(HttpStatus.ACCEPTED, r1.statusCode)

        val r2 = rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/finalize"))
                .body(
                    PayRunController.StartFinalizeRequest(
                        payPeriodId = "pp-1",
                        employeeIds = listOf("e-2"),
                        requestedPayRunId = "run-it-3-different",
                        idempotencyKey = "idem-1",
                    ),
                ),
            PayRunController.StartFinalizeResponse::class.java,
        )

        assertEquals(HttpStatus.ACCEPTED, r2.statusCode)
        assertEquals(r1.body!!.payRunId, r2.body!!.payRunId)
        assertEquals(false, r2.body!!.created)
    }

    @Test
    fun `business key collision returns existing payrun instead of 500`() {
        val employerId = "emp-1"

        val r1 = rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/finalize"))
                .body(
                    PayRunController.StartFinalizeRequest(
                        payPeriodId = "pp-1",
                        employeeIds = listOf("e-1"),
                        requestedPayRunId = "run-it-business-1",
                    ),
                ),
            PayRunController.StartFinalizeResponse::class.java,
        )
        assertEquals(HttpStatus.ACCEPTED, r1.statusCode)

        // Second request uses a different requestedPayRunId, but the same business key:
        // (employerId, payPeriodId, runType=REGULAR, runSequence=1).
        val r2 = rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/finalize"))
                .body(
                    PayRunController.StartFinalizeRequest(
                        payPeriodId = "pp-1",
                        employeeIds = listOf("e-2"),
                        requestedPayRunId = "run-it-business-2",
                    ),
                ),
            PayRunController.StartFinalizeResponse::class.java,
        )

        assertEquals(HttpStatus.ACCEPTED, r2.statusCode)
        assertEquals(r1.body!!.payRunId, r2.body!!.payRunId)
        assertEquals(false, r2.body!!.created)
    }

    @Test
    fun `stale RUNNING item is requeued and completed`() {
        val employerId = "emp-1"

        val start = rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/finalize"))
                .body(
                    PayRunController.StartFinalizeRequest(
                        payPeriodId = "pp-1",
                        employeeIds = listOf("e-1"),
                        requestedPayRunId = "run-it-4",
                    ),
                ),
            PayRunController.StartFinalizeResponse::class.java,
        )
        assertEquals(HttpStatus.ACCEPTED, start.statusCode)

        // Simulate a stuck RUNNING row from a prior crash.
        jdbcTemplate.update(
            """
            UPDATE pay_run_item
            SET status = 'RUNNING',
                updated_at = TIMESTAMP '2000-01-01 00:00:00',
                started_at = TIMESTAMP '2000-01-01 00:00:00'
            WHERE employer_id = ? AND pay_run_id = ? AND employee_id = ?
            """.trimIndent(),
            employerId,
            "run-it-4",
            "e-1",
        )

        val execHeaders = InternalAuthTestSupport.internalAuthHeaders()
        val exec = rest.exchange(
            RequestEntity.post(
                URI.create(
                    "/employers/$employerId/payruns/internal/run-it-4/execute?batchSize=10&maxItems=50&maxMillis=2000&requeueStaleMillis=1000",
                ),
            )
                .headers(execHeaders)
                .build(),
            Map::class.java,
        )
        assertEquals(HttpStatus.OK, exec.statusCode)

        val status = rest.getForEntity(
            "/employers/$employerId/payruns/run-it-4",
            PayRunController.PayRunStatusResponse::class.java,
        )
        assertEquals(PayRunStatus.FINALIZED, status.body!!.status)
        assertEquals(1, status.body!!.counts.succeeded)

        val outboxCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM outbox_event WHERE status = 'PENDING'",
            Long::class.java,
        ) ?: 0L
        assertEquals(2L, outboxCount) // 1 payrun + 1 paycheck
    }

    @Test
    fun `approve then initiate payments twice enqueues only one payment-request event per paycheck`() {
        val employerId = "emp-1"

        val start = rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/finalize"))
                .body(
                    PayRunController.StartFinalizeRequest(
                        payPeriodId = "pp-1",
                        employeeIds = listOf("e-1"),
                        requestedPayRunId = "run-it-pay-1",
                    ),
                ),
            PayRunController.StartFinalizeResponse::class.java,
        )
        assertEquals(HttpStatus.ACCEPTED, start.statusCode)

        val execHeaders = InternalAuthTestSupport.internalAuthHeaders()
        rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/internal/run-it-pay-1/execute?batchSize=10"))
                .headers(execHeaders)
                .build(),
            Map::class.java,
        )

        val approve = rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/run-it-pay-1/approve"))
                .build(),
            Map::class.java,
        )
        assertEquals(HttpStatus.OK, approve.statusCode)

        val p1 = rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/run-it-pay-1/payments/initiate"))
                .build(),
            Map::class.java,
        )
        assertEquals(HttpStatus.OK, p1.statusCode)

        val p2 = rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/run-it-pay-1/payments/initiate"))
                .build(),
            Map::class.java,
        )
        assertEquals(HttpStatus.OK, p2.statusCode)

        val requestEventCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM outbox_event
            WHERE status = 'PENDING'
              AND topic = 'paycheck.payment.requested'
              AND event_type = 'PaycheckPaymentRequested'
            """.trimIndent(),
            Long::class.java,
        ) ?: 0L

        // One succeeded paycheck -> one payment request event.
        assertEquals(1L, requestEventCount)
    }
}
