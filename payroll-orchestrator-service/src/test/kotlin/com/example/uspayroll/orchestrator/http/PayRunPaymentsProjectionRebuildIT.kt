package com.example.uspayroll.orchestrator.http

import com.example.uspayroll.messaging.events.payments.PaycheckPaymentLifecycleStatus
import com.example.uspayroll.orchestrator.client.PaymentsQueryClient
import com.example.uspayroll.orchestrator.payments.PaymentsQueryClientTestConfig
import com.example.uspayroll.orchestrator.payrun.model.PaymentStatus
import com.example.uspayroll.orchestrator.support.InternalAuthTestSupport
import com.example.uspayroll.orchestrator.support.StubClientsTestConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
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
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(StubClientsTestConfig::class, PaymentsQueryClientTestConfig::class)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@TestPropertySource(
    properties = [
        "orchestrator.internal-auth.jwt-keys.k1=dev-internal-token",
        "orchestrator.internal-auth.jwt-default-kid=k1",
        "orchestrator.payrun.execute.enabled=true",
    ],
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class PayRunPaymentsProjectionRebuildIT(
    private val rest: TestRestTemplate,
    private val jdbcTemplate: JdbcTemplate,
    private val paymentsQueryClient: PaymentsQueryClient,
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
    fun `rebuild payment projection persists paycheck and payrun payment statuses`() {
        // Use a class-specific employerId to avoid cross-test collisions on payrun business keys.
        val employerId = "emp-payments-proj"
        val payRunId = "run-proj-rebuild-${UUID.randomUUID()}"

        // Create payrun with two succeeded paychecks.
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

        val execHeaders = InternalAuthTestSupport.internalAuthHeaders()
        rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/internal/$payRunId/execute?batchSize=10"))
                .headers(execHeaders)
                .build(),
            Map::class.java,
        )

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

        val paycheck1 = paycheckId(employerId, payRunId, "e-1")
        val paycheck2 = paycheckId(employerId, payRunId, "e-2")

        // Simulate drift.
        jdbcTemplate.update(
            "UPDATE paycheck SET payment_status = ? WHERE employer_id = ? AND paycheck_id IN (?, ?)",
            PaymentStatus.PAYING.name,
            employerId,
            paycheck1,
            paycheck2,
        )

        Mockito.`when`(paymentsQueryClient.listPaymentsForPayRun(employerId, payRunId)).thenReturn(
            listOf(
                PaymentsQueryClient.PaycheckPaymentView(
                    employerId = employerId,
                    paymentId = "pmt-$paycheck1",
                    paycheckId = paycheck1,
                    payRunId = payRunId,
                    employeeId = "e-1",
                    payPeriodId = "pp-1",
                    currency = "USD",
                    netCents = 100,
                    status = PaycheckPaymentLifecycleStatus.SETTLED,
                    attempts = 0,
                ),
                PaymentsQueryClient.PaycheckPaymentView(
                    employerId = employerId,
                    paymentId = "pmt-$paycheck2",
                    paycheckId = paycheck2,
                    payRunId = payRunId,
                    employeeId = "e-2",
                    payPeriodId = "pp-1",
                    currency = "USD",
                    netCents = 100,
                    status = PaycheckPaymentLifecycleStatus.FAILED,
                    attempts = 2,
                ),
            ),
        )

        val rebuild = rest.exchange(
            RequestEntity.post(
                URI.create(
                    "/employers/$employerId/payruns/internal/$payRunId/reconcile/payments/rebuild-projection?persist=true",
                ),
            ).headers(execHeaders).build(),
            Map::class.java,
        )
        assertEquals(HttpStatus.OK, rebuild.statusCode)

        val p1 = paycheckPaymentStatus(employerId, paycheck1)
        val p2 = paycheckPaymentStatus(employerId, paycheck2)
        assertEquals(PaymentStatus.PAID.name, p1)
        assertEquals(PaymentStatus.FAILED.name, p2)

        // paycheck_payment projection should also be updated.
        val proj1 = paycheckPaymentProjectionStatus(employerId, paycheck1)
        val proj2 = paycheckPaymentProjectionStatus(employerId, paycheck2)
        assertEquals("SETTLED", proj1)
        assertEquals("FAILED", proj2)

        val runStatus = jdbcTemplate.queryForObject(
            "SELECT payment_status FROM pay_run WHERE employer_id = ? AND pay_run_id = ?",
            String::class.java,
            employerId,
            payRunId,
        )
        assertEquals(PaymentStatus.PARTIALLY_PAID.name, runStatus)
    }

    @Test
    fun `rebuild payment projection can be dry-run with persist=false`() {
        val employerId = "emp-payments-proj"
        val payRunId = "run-proj-rebuild-${UUID.randomUUID()}"

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

        val execHeaders = InternalAuthTestSupport.internalAuthHeaders()
        rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/internal/$payRunId/execute?batchSize=10"))
                .headers(execHeaders)
                .build(),
            Map::class.java,
        )

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

        val paycheckId = paycheckId(employerId, payRunId, "e-1")

        val before = paycheckPaymentStatus(employerId, paycheckId)
        assertNotEquals(PaymentStatus.PAID.name, before)

        Mockito.`when`(paymentsQueryClient.listPaymentsForPayRun(employerId, payRunId)).thenReturn(
            listOf(
                PaymentsQueryClient.PaycheckPaymentView(
                    employerId = employerId,
                    paymentId = "pmt-$paycheckId",
                    paycheckId = paycheckId,
                    payRunId = payRunId,
                    employeeId = "e-1",
                    payPeriodId = "pp-1",
                    currency = "USD",
                    netCents = 100,
                    status = PaycheckPaymentLifecycleStatus.SETTLED,
                    attempts = 0,
                ),
            ),
        )

        val rebuild = rest.exchange(
            RequestEntity.post(
                URI.create(
                    "/employers/$employerId/payruns/internal/$payRunId/reconcile/payments/rebuild-projection?persist=false",
                ),
            ).headers(execHeaders).build(),
            Map::class.java,
        )
        assertEquals(HttpStatus.OK, rebuild.statusCode)

        // DB should be unchanged.
        val after = paycheckPaymentStatus(employerId, paycheckId)
        assertEquals(before, after)

        val afterProjection = paycheckPaymentProjectionStatus(employerId, paycheckId)
        // Initiation seeds CREATED; dry-run rebuild should not alter it.
        assertEquals("CREATED", afterProjection)
    }

    private fun paycheckId(employerId: String, payRunId: String, employeeId: String): String = jdbcTemplate.queryForObject(
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

    private fun paycheckPaymentStatus(employerId: String, paycheckId: String): String = jdbcTemplate.queryForObject(
        "SELECT payment_status FROM paycheck WHERE employer_id = ? AND paycheck_id = ?",
        String::class.java,
        employerId,
        paycheckId,
    )!!

    private fun paycheckPaymentProjectionStatus(employerId: String, paycheckId: String): String = jdbcTemplate.queryForObject(
        "SELECT status FROM paycheck_payment WHERE employer_id = ? AND paycheck_id = ?",
        String::class.java,
        employerId,
        paycheckId,
    )!!
}
