package com.example.usbilling.orchestrator.http

import com.example.usbilling.orchestrator.support.InternalAuthTestSupport
import com.example.usbilling.orchestrator.support.RetroMutableStubClientsTestConfig
import com.example.usbilling.orchestrator.support.RetroMutableStubClientsTestConfig.RetroStubState
import com.example.usbilling.payroll.model.PaycheckResult
import com.example.usbilling.payroll.model.audit.InputFingerprint
import com.example.usbilling.payroll.model.audit.PaycheckAudit
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
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
@Import(RetroMutableStubClientsTestConfig::class)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@TestPropertySource(
    properties = [
        "orchestrator.internal-auth.jwt-keys.k1=dev-internal-token",
        "orchestrator.internal-auth.jwt-default-kid=k1",
        "orchestrator.payrun.execute.enabled=true",
    ],
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class PayRunRetroAdjustmentWorkflowIT(
    private val rest: TestRestTemplate,
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
    private val stubState: RetroStubState,
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
    fun `retro adjustment produces delta paycheck when compensation changes retroactively`() {
        val employerId = "emp-1"

        val sourcePayRunId = "run-retro-src-comp"
        startAndExecutePayRun(employerId, sourcePayRunId)
        approvePayRun(employerId, sourcePayRunId)

        val originalPaycheckId = paycheckIdForEmployee(employerId, sourcePayRunId, "e-1")

        // Simulate an effective-dated HR update in the past (same asOfDate now yields different snapshot).
        stubState.applyOverrides.set(true)
        stubState.overrideAnnualSalaryCents.set(156_000_00L)
        stubState.overrideWorkState.set("CA")
        stubState.overrideAdditionalWithholdingCents.set(null)

        val adjustmentPayRunId = "run-retro-adj-comp"
        val retro = rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/$sourcePayRunId/retro"))
                .body(PayRunController.StartCorrectionRequest(requestedPayRunId = adjustmentPayRunId)),
            PayRunController.StartRetroAdjustmentResponse::class.java,
        )
        assertEquals(HttpStatus.ACCEPTED, retro.statusCode)
        assertEquals(adjustmentPayRunId, retro.body!!.adjustmentPayRunId)

        val deltaPaycheckId = paycheckIdForEmployee(employerId, adjustmentPayRunId, "e-1")
        val delta = rest.getForEntity(
            "/employers/$employerId/paychecks/$deltaPaycheckId",
            PaycheckResult::class.java,
        ).body!!

        // Expected: annual salary 130k -> 156k => biweekly cash gross 5000.00 -> 6000.00
        assertEquals(1_000_00L, delta.gross.amount)

        // Federal 10% and CA 5% apply in source snapshot; delta is +100 +50.
        assertEquals(150_00L, delta.employeeTaxes.sumOf { it.amount.amount })

        // Net delta = gross delta - tax delta.
        assertEquals(850_00L, delta.net.amount)

        // Linkage to original.
        val correctionOfPaycheckId = jdbcTemplate.queryForObject(
            "SELECT correction_of_paycheck_id FROM paycheck WHERE employer_id = ? AND paycheck_id = ?",
            String::class.java,
            employerId,
            deltaPaycheckId,
        )
        assertEquals(originalPaycheckId, correctionOfPaycheckId)

        // Audit fingerprints must be populated (not UNKNOWN).
        val execHeaders = InternalAuthTestSupport.internalAuthHeaders()
        val auditResponse = rest.exchange(
            RequestEntity.get(URI.create("/employers/$employerId/paychecks/internal/$deltaPaycheckId/audit"))
                .headers(execHeaders)
                .build(),
            PaycheckAudit::class.java,
        )
        assertEquals(HttpStatus.OK, auditResponse.statusCode)
        val audit = auditResponse.body!!
        assertNotEquals(InputFingerprint.UNKNOWN, audit.employeeSnapshotFingerprint)
        assertNotEquals(InputFingerprint.UNKNOWN, audit.taxContextFingerprint)
    }

    @Test
    fun `retro adjustment produces delta paycheck when work state changes retroactively`() {
        val employerId = "emp-1"

        // Use state taxes and avoid additional withholding for this scenario.
        stubState.includeStateTaxes.set(true)
        stubState.applyOverrides.set(false)
        stubState.overrideAnnualSalaryCents.set(null)
        stubState.overrideWorkState.set(null)
        stubState.overrideAdditionalWithholdingCents.set(null)

        val sourcePayRunId = "run-retro-src-state"
        startAndExecutePayRun(employerId, sourcePayRunId)
        approvePayRun(employerId, sourcePayRunId)

        // Only update the workState (NY) without changing salary.
        stubState.applyOverrides.set(true)
        stubState.overrideWorkState.set("NY")
        stubState.overrideAnnualSalaryCents.set(130_000_00L)
        stubState.overrideAdditionalWithholdingCents.set(null)

        val adjustmentPayRunId = "run-retro-adj-state"
        val retro = rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/$sourcePayRunId/retro"))
                .body(PayRunController.StartCorrectionRequest(requestedPayRunId = adjustmentPayRunId)),
            PayRunController.StartRetroAdjustmentResponse::class.java,
        )
        assertEquals(HttpStatus.ACCEPTED, retro.statusCode)

        val deltaPaycheckId = paycheckIdForEmployee(employerId, adjustmentPayRunId, "e-1")
        val delta = rest.getForEntity(
            "/employers/$employerId/paychecks/$deltaPaycheckId",
            PaycheckResult::class.java,
        ).body!!

        // Gross unchanged (salary delta is represented in the other test).
        // This test focuses on state tax applicability (CA -> NY).
        // CA 5% on 5000.00 is 250.00; NY 7% is 350.00 => delta employee tax +100.00.
        assertEquals(0L, delta.gross.amount)
        assertEquals(100_00L, delta.employeeTaxes.sumOf { it.amount.amount })
        assertEquals(-100_00L, delta.net.amount)
    }

    @Test
    fun `retro adjustment produces delta paycheck when W-4 additional withholding changes retroactively`() {
        val employerId = "emp-1"

        // Only federal taxes so the per-employee extra is not applied multiple times.
        stubState.includeStateTaxes.set(false)
        stubState.applyOverrides.set(false)
        stubState.overrideAnnualSalaryCents.set(null)
        stubState.overrideWorkState.set(null)
        stubState.overrideAdditionalWithholdingCents.set(null)

        val sourcePayRunId = "run-retro-src-w4"
        startAndExecutePayRun(employerId, sourcePayRunId)
        approvePayRun(employerId, sourcePayRunId)

        // Enable updated snapshot with +$100 extra withholding.
        stubState.applyOverrides.set(true)
        stubState.overrideAdditionalWithholdingCents.set(100_00L)
        stubState.overrideAnnualSalaryCents.set(130_000_00L)
        stubState.overrideWorkState.set("CA")

        val adjustmentPayRunId = "run-retro-adj-w4"
        val retro = rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/$sourcePayRunId/retro"))
                .body(PayRunController.StartCorrectionRequest(requestedPayRunId = adjustmentPayRunId)),
            PayRunController.StartRetroAdjustmentResponse::class.java,
        )
        assertEquals(HttpStatus.ACCEPTED, retro.statusCode)

        val deltaPaycheckId = paycheckIdForEmployee(employerId, adjustmentPayRunId, "e-1")
        val delta = rest.getForEntity(
            "/employers/$employerId/paychecks/$deltaPaycheckId",
            PaycheckResult::class.java,
        ).body!!

        // Gross unchanged, federal tax increased by $100.
        assertEquals(0L, delta.gross.amount)
        assertEquals(100_00L, delta.employeeTaxes.sumOf { it.amount.amount })
        assertEquals(-100_00L, delta.net.amount)
    }

    private fun startAndExecutePayRun(employerId: String, payRunId: String) {
        val start = rest.exchange(
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
        assertEquals(HttpStatus.ACCEPTED, start.statusCode)
        assertEquals(payRunId, start.body!!.payRunId)

        val execHeaders = InternalAuthTestSupport.internalAuthHeaders()
        val exec = rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/internal/$payRunId/execute?batchSize=10"))
                .headers(execHeaders)
                .build(),
            Map::class.java,
        )
        assertEquals(HttpStatus.OK, exec.statusCode)

        val status = rest.getForEntity(
            "/employers/$employerId/payruns/$payRunId",
            PayRunController.PayRunStatusResponse::class.java,
        )
        assertEquals(HttpStatus.OK, status.statusCode)
        assertEquals(com.example.usbilling.orchestrator.payrun.model.PayRunStatus.FINALIZED, status.body!!.status)
    }

    private fun approvePayRun(employerId: String, payRunId: String) {
        val approve = rest.postForEntity(
            "/employers/$employerId/payruns/$payRunId/approve",
            null,
            PayRunController.ApprovePayRunResponse::class.java,
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
