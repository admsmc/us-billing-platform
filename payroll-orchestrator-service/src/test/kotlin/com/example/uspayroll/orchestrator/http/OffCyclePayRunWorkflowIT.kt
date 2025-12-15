package com.example.uspayroll.orchestrator.http

import com.example.uspayroll.orchestrator.payrun.model.PayRunStatus
import com.example.uspayroll.orchestrator.support.StubClientsTestConfig
import com.example.uspayroll.payroll.model.PaycheckResult
import com.example.uspayroll.payroll.model.audit.PaycheckAudit
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
        "orchestrator.payrun.execute.enabled=true",
    ],
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class OffCyclePayRunWorkflowIT(
    private val rest: TestRestTemplate,
    private val jdbcTemplate: JdbcTemplate,
) {

    @Test
    fun `off-cycle payrun pays only explicit supplemental earnings and can be approved and paid`() {
        val employerId = "emp-1"

        val start = rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/finalize"))
                .body(
                    PayRunController.StartFinalizeRequest(
                        payPeriodId = "pp-1",
                        employeeIds = listOf("e-1"),
                        runType = "OFF_CYCLE",
                        requestedPayRunId = "run-offcycle-1",
                        earningOverridesByEmployeeId = mapOf(
                            "e-1" to listOf(
                                com.example.uspayroll.orchestrator.payrun.PayRunEarningOverride(
                                    code = "BONUS",
                                    units = 1.0,
                                    amountCents = 1_000_00L,
                                ),
                            ),
                        ),
                    ),
                ),
            PayRunController.StartFinalizeResponse::class.java,
        )

        assertEquals(HttpStatus.ACCEPTED, start.statusCode)
        assertEquals("run-offcycle-1", start.body!!.payRunId)

        val execHeaders = HttpHeaders().apply { set("X-Internal-Token", "dev-internal-token") }
        val exec = rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/internal/run-offcycle-1/execute?batchSize=10"))
                .headers(execHeaders)
                .build(),
            Map::class.java,
        )
        assertEquals(HttpStatus.OK, exec.statusCode)

        val status = rest.getForEntity(
            "/employers/$employerId/payruns/run-offcycle-1",
            PayRunController.PayRunStatusResponse::class.java,
        )
        assertEquals(PayRunStatus.FINALIZED, status.body!!.status)

        val paycheckId = jdbcTemplate.queryForObject(
            """
            SELECT paycheck_id
            FROM pay_run_item
            WHERE employer_id = ? AND pay_run_id = ? AND employee_id = ?
            """.trimIndent(),
            String::class.java,
            employerId,
            "run-offcycle-1",
            "e-1",
        )
        assertNotNull(paycheckId)

        val paycheck = rest.getForEntity(
            "/employers/$employerId/paychecks/$paycheckId",
            PaycheckResult::class.java,
        )
        assertEquals(HttpStatus.OK, paycheck.statusCode)

        // Base salary is suppressed; only the explicit bonus is paid.
        assertEquals(1_000_00L, paycheck.body!!.gross.amount)
        assertEquals(1, paycheck.body!!.earnings.size)
        assertEquals("BONUS", paycheck.body!!.earnings.single().code.value)

        // Audit should clearly show supplemental wages amount.
        val audit = rest.exchange(
            RequestEntity.get(URI.create("/employers/$employerId/paychecks/internal/$paycheckId/audit"))
                .headers(execHeaders)
                .build(),
            PaycheckAudit::class.java,
        )
        assertEquals(HttpStatus.OK, audit.statusCode)
        assertEquals(1_000_00L, audit.body!!.cashGrossCents)
        assertEquals(1_000_00L, audit.body!!.supplementalWagesCents)

        val approve = rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/run-offcycle-1/approve"))
                .build(),
            PayRunController.ApprovePayRunResponse::class.java,
        )
        assertEquals(HttpStatus.OK, approve.statusCode)

        val pay = rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/run-offcycle-1/payments/initiate"))
                .build(),
            PayRunController.InitiatePaymentsResponse::class.java,
        )
        assertEquals(HttpStatus.OK, pay.statusCode)
    }
}
