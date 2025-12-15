package com.example.uspayroll.orchestrator.events

import com.example.uspayroll.messaging.events.reporting.PaycheckLedgerAction
import com.example.uspayroll.messaging.events.reporting.PaycheckLedgerEvent
import com.example.uspayroll.orchestrator.support.RetroMutableStubClientsTestConfig
import com.example.uspayroll.orchestrator.support.RetroMutableStubClientsTestConfig.RetroStubState
import com.fasterxml.jackson.databind.ObjectMapper
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
@Import(RetroMutableStubClientsTestConfig::class)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@TestPropertySource(
    properties = [
        "orchestrator.internal-auth.shared-secret=dev-internal-token",
        "orchestrator.payrun.execute.enabled=true",
    ],
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class PaycheckLedgerAdjustmentEventsIT(
    private val rest: TestRestTemplate,
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
    private val stubState: RetroStubState,
) {

    @Test
    fun `approving an adjustment payrun enqueues ADJUSTED paycheck ledger events with correction linkage`() {
        val employerId = "emp-1"

        val sourcePayRunId = "run-ledger-adj-src"
        startAndExecutePayRun(employerId = employerId, payRunId = sourcePayRunId)
        approvePayRun(employerId = employerId, payRunId = sourcePayRunId)

        val originalPaycheckId = paycheckIdForEmployee(employerId = employerId, payRunId = sourcePayRunId, employeeId = "e-1")

        // Change effective-dated snapshot so retro adjustment produces a delta.
        stubState.applyOverrides.set(true)
        stubState.overrideAnnualSalaryCents.set(156_000_00L)
        stubState.overrideWorkState.set("CA")
        stubState.overrideAdditionalWithholdingCents.set(null)

        val adjustmentPayRunId = "run-ledger-adj-1"
        val retro = rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/$sourcePayRunId/retro"))
                .body(com.example.uspayroll.orchestrator.http.PayRunController.StartCorrectionRequest(requestedPayRunId = adjustmentPayRunId)),
            com.example.uspayroll.orchestrator.http.PayRunController.StartRetroAdjustmentResponse::class.java,
        )
        assertEquals(HttpStatus.ACCEPTED, retro.statusCode)

        approvePayRun(employerId = employerId, payRunId = adjustmentPayRunId)

        val deltaPaycheckId = paycheckIdForEmployee(employerId = employerId, payRunId = adjustmentPayRunId, employeeId = "e-1")

        val payload = jdbcTemplate.queryForObject(
            """
            SELECT payload_json
            FROM outbox_event
            WHERE event_type = 'PaycheckLedger' AND aggregate_id = ?
            ORDER BY created_at DESC
            LIMIT 1
            """.trimIndent(),
            String::class.java,
            deltaPaycheckId,
        )
        assertNotNull(payload)

        val evt = objectMapper.readValue(payload, PaycheckLedgerEvent::class.java)
        assertEquals(PaycheckLedgerAction.ADJUSTED, evt.action)
        assertEquals(adjustmentPayRunId, evt.payRunId)
        assertEquals(deltaPaycheckId, evt.paycheckId)
        assertEquals(originalPaycheckId, evt.correctionOfPaycheckId)
        assertEquals(sourcePayRunId, evt.correctionOfPayRunId)
    }

    private fun startAndExecutePayRun(employerId: String, payRunId: String) {
        val start = rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/finalize"))
                .body(
                    com.example.uspayroll.orchestrator.http.PayRunController.StartFinalizeRequest(
                        payPeriodId = "pp-1",
                        employeeIds = listOf("e-1"),
                        requestedPayRunId = payRunId,
                    ),
                ),
            com.example.uspayroll.orchestrator.http.PayRunController.StartFinalizeResponse::class.java,
        )
        assertEquals(HttpStatus.ACCEPTED, start.statusCode)

        val execHeaders = HttpHeaders().apply { set("X-Internal-Token", "dev-internal-token") }
        val exec = rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/internal/$payRunId/execute?batchSize=10"))
                .headers(execHeaders)
                .build(),
            Map::class.java,
        )
        assertEquals(HttpStatus.OK, exec.statusCode)

        val status = rest.getForEntity(
            "/employers/$employerId/payruns/$payRunId",
            com.example.uspayroll.orchestrator.http.PayRunController.PayRunStatusResponse::class.java,
        )
        assertEquals(HttpStatus.OK, status.statusCode)
        assertEquals(com.example.uspayroll.orchestrator.payrun.model.PayRunStatus.FINALIZED, status.body!!.status)
    }

    private fun approvePayRun(employerId: String, payRunId: String) {
        val approve = rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/$payRunId/approve")).build(),
            com.example.uspayroll.orchestrator.http.PayRunController.ApprovePayRunResponse::class.java,
        )
        assertEquals(HttpStatus.OK, approve.statusCode)
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
