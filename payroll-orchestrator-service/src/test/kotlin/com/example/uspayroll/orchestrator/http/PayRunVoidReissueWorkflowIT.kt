package com.example.uspayroll.orchestrator.http

import com.example.uspayroll.messaging.events.payments.PaycheckPaymentLifecycleStatus
import com.example.uspayroll.messaging.events.payments.PaycheckPaymentStatusChangedEvent
import com.example.uspayroll.orchestrator.payments.PaymentStatusProjectionService
import com.example.uspayroll.orchestrator.payrun.model.PayRunStatus
import com.example.uspayroll.orchestrator.support.InternalAuthTestSupport
import com.example.uspayroll.orchestrator.support.StubClientsTestConfig
import com.example.uspayroll.payroll.model.PaycheckResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
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
class PayRunVoidReissueWorkflowIT(
    private val rest: TestRestTemplate,
    private val jdbcTemplate: JdbcTemplate,
    private val paymentProjection: PaymentStatusProjectionService,
) {

    @Test
    fun `create payrun then pay then void then reissue`() {
        val employerId = "emp-1"
        val payRunId = "run-c1-${UUID.randomUUID()}"
        val runSequence = ((UUID.randomUUID().mostSignificantBits ushr 1) % 1_000_000L).toInt() + 1

        // 1) Create + finalize
        val start = rest.exchange(
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
        assertEquals(HttpStatus.ACCEPTED, start.statusCode)

        val execHeaders = InternalAuthTestSupport.internalAuthHeaders()
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

        val originalPaycheckId = jdbcTemplate.queryForObject(
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
        assertNotNull(originalPaycheckId)

        // Mark as PAID (simulate payments-service status changed event).
        paymentProjection.applyPaymentStatusChanged(
            PaycheckPaymentStatusChangedEvent(
                eventId = "evt-c1-paid-${UUID.randomUUID()}",
                occurredAt = Instant.now(),
                employerId = employerId,
                payRunId = payRunId,
                paycheckId = originalPaycheckId!!,
                paymentId = "pmt-$originalPaycheckId",
                status = PaycheckPaymentLifecycleStatus.SETTLED,
            ),
        )

        val paidStatus = jdbcTemplate.queryForObject(
            "SELECT payment_status FROM pay_run WHERE employer_id = ? AND pay_run_id = ?",
            String::class.java,
            employerId,
            payRunId,
        )
        assertEquals("PAID", paidStatus)

        val originalPaycheck = rest.getForEntity(
            "/employers/$employerId/paychecks/$originalPaycheckId",
            PaycheckResult::class.java,
        ).body!!

        // 2) VOID
        val voidResp = rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/$payRunId/void"))
                .body(PayRunController.StartCorrectionRequest()),
            PayRunController.StartCorrectionResponse::class.java,
        )
        assertEquals(HttpStatus.ACCEPTED, voidResp.statusCode)
        val voidPayRunId = voidResp.body!!.correctionPayRunId

        val voidStatus = rest.getForEntity(
            "/employers/$employerId/payruns/$voidPayRunId",
            PayRunController.PayRunStatusResponse::class.java,
        )
        assertEquals(PayRunStatus.FINALIZED, voidStatus.body!!.status)

        val voidPaycheckId = jdbcTemplate.queryForObject(
            """
            SELECT paycheck_id
            FROM pay_run_item
            WHERE employer_id = ? AND pay_run_id = ? AND employee_id = ?
            """.trimIndent(),
            String::class.java,
            employerId,
            voidPayRunId,
            "e-1",
        )
        assertNotNull(voidPaycheckId)

        val voidPaycheck = rest.getForEntity(
            "/employers/$employerId/paychecks/$voidPaycheckId",
            PaycheckResult::class.java,
        ).body!!

        assertEquals(-originalPaycheck.gross.amount, voidPaycheck.gross.amount)
        assertEquals(-originalPaycheck.net.amount, voidPaycheck.net.amount)

        val linkedOriginalForVoid = jdbcTemplate.queryForObject(
            "SELECT correction_of_paycheck_id FROM paycheck WHERE employer_id = ? AND paycheck_id = ?",
            String::class.java,
            employerId,
            voidPaycheckId,
        )
        assertEquals(originalPaycheckId, linkedOriginalForVoid)

        val linkedRunForVoid = jdbcTemplate.queryForObject(
            "SELECT correction_of_pay_run_id FROM pay_run WHERE employer_id = ? AND pay_run_id = ?",
            String::class.java,
            employerId,
            voidPayRunId,
        )
        assertEquals(payRunId, linkedRunForVoid)

        // Cannot initiate payments for void.
        val voidPayAttempt = rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/$voidPayRunId/payments/initiate"))
                .build(),
            Any::class.java,
        )
        assertEquals(HttpStatus.CONFLICT, voidPayAttempt.statusCode)

        // 3) REISSUE
        val reissueResp = rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/$payRunId/reissue"))
                .body(PayRunController.StartCorrectionRequest()),
            PayRunController.StartCorrectionResponse::class.java,
        )
        assertEquals(HttpStatus.ACCEPTED, reissueResp.statusCode)
        val reissuePayRunId = reissueResp.body!!.correctionPayRunId

        val reissuePaycheckId = jdbcTemplate.queryForObject(
            """
            SELECT paycheck_id
            FROM pay_run_item
            WHERE employer_id = ? AND pay_run_id = ? AND employee_id = ?
            """.trimIndent(),
            String::class.java,
            employerId,
            reissuePayRunId,
            "e-1",
        )
        assertNotNull(reissuePaycheckId)

        val reissuePaycheck = rest.getForEntity(
            "/employers/$employerId/paychecks/$reissuePaycheckId",
            PaycheckResult::class.java,
        ).body!!

        // Reissue clones the original amounts.
        assertEquals(originalPaycheck.gross.amount, reissuePaycheck.gross.amount)
        assertEquals(originalPaycheck.net.amount, reissuePaycheck.net.amount)

        val linkedOriginalForReissue = jdbcTemplate.queryForObject(
            "SELECT correction_of_paycheck_id FROM paycheck WHERE employer_id = ? AND paycheck_id = ?",
            String::class.java,
            employerId,
            reissuePaycheckId,
        )
        assertEquals(originalPaycheckId, linkedOriginalForReissue)

        // Reissue can be approved + paid.
        val approveReissue = rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/$reissuePayRunId/approve"))
                .build(),
            Map::class.java,
        )
        assertEquals(HttpStatus.OK, approveReissue.statusCode)

        val payReissue = rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/$reissuePayRunId/payments/initiate"))
                .build(),
            Map::class.java,
        )
        assertEquals(HttpStatus.OK, payReissue.statusCode)
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
}
