package com.example.uspayroll.orchestrator.http

import com.example.uspayroll.orchestrator.client.HrClient
import com.example.uspayroll.orchestrator.client.LaborStandardsClient
import com.example.uspayroll.orchestrator.client.TaxClient
import com.example.uspayroll.orchestrator.payrun.model.PayRunStatus
import com.example.uspayroll.payroll.model.BaseCompensation
import com.example.uspayroll.payroll.model.EmployeeSnapshot
import com.example.uspayroll.payroll.model.FilingStatus
import com.example.uspayroll.payroll.model.PayFrequency
import com.example.uspayroll.payroll.model.PayPeriod
import com.example.uspayroll.payroll.model.TaxContext
import com.example.uspayroll.payroll.model.garnishment.GarnishmentOrder
import com.example.uspayroll.shared.EmployeeId
import com.example.uspayroll.shared.EmployerId
import com.example.uspayroll.payroll.model.LocalDateRange
import com.example.uspayroll.shared.Money
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.RequestEntity
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import java.net.URI
import java.time.LocalDate

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class PayRunWorkflowIT(
    private val rest: TestRestTemplate,
    private val jdbcTemplate: JdbcTemplate,
) {

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
                    )
                ),
            PayRunController.StartFinalizeResponse::class.java,
        )

        assertEquals(HttpStatus.ACCEPTED, start.statusCode)
        assertEquals("run-it-1", start.body!!.payRunId)

        val execHeaders = HttpHeaders().apply { set("X-Internal-Token", "dev-internal-token") }
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
            Any::class.java,
        )
        assertEquals(HttpStatus.OK, paycheck.statusCode)
        assertNotNull(paycheck.body)
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
                    )
                ),
            PayRunController.StartFinalizeResponse::class.java,
        )

        assertEquals(HttpStatus.ACCEPTED, start.statusCode)

        val execHeaders = HttpHeaders().apply { set("X-Internal-Token", "dev-internal-token") }
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
                    )
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
                    )
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
                    )
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

        val execHeaders = HttpHeaders().apply { set("X-Internal-Token", "dev-internal-token") }
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
                    )
                ),
            PayRunController.StartFinalizeResponse::class.java,
        )
        assertEquals(HttpStatus.ACCEPTED, start.statusCode)

        val execHeaders = HttpHeaders().apply { set("X-Internal-Token", "dev-internal-token") }
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

    @TestConfiguration
    class StubClientsConfig {

        @Bean
        @Primary
        fun stubHrClient(): HrClient = object : HrClient {
            override fun getEmployeeSnapshot(
                employerId: EmployerId,
                employeeId: EmployeeId,
                asOfDate: LocalDate,
            ): EmployeeSnapshot? {
                if (employeeId.value == "e-bad") return null

                return EmployeeSnapshot(
                    employerId = employerId,
                    employeeId = employeeId,
                    homeState = "CA",
                    workState = "CA",
                    filingStatus = FilingStatus.SINGLE,
                    baseCompensation = BaseCompensation.Salaried(
                        annualSalary = Money(120_000_00L),
                        frequency = PayFrequency.BIWEEKLY,
                    ),
                    workCity = "Detroit",
                )
            }

            override fun getPayPeriod(
                employerId: EmployerId,
                payPeriodId: String,
            ): PayPeriod? {
                if (payPeriodId != "pp-1") return null

                return PayPeriod(
                    id = payPeriodId,
                    employerId = employerId,
                    dateRange = LocalDateRange(
                        startInclusive = LocalDate.parse("2025-01-01"),
                        endInclusive = LocalDate.parse("2025-01-14"),
                    ),
                    checkDate = LocalDate.parse("2025-01-17"),
                    frequency = PayFrequency.BIWEEKLY,
                )
            }

            override fun getGarnishmentOrders(
                employerId: EmployerId,
                employeeId: EmployeeId,
                asOfDate: LocalDate,
            ): List<GarnishmentOrder> = emptyList()
        }

        @Bean
        @Primary
        fun stubTaxClient(): TaxClient = object : TaxClient {
            override fun getTaxContext(
                employerId: EmployerId,
                asOfDate: LocalDate,
                localityCodes: List<String>,
            ): TaxContext = TaxContext()
        }

        @Bean
        @Primary
        fun stubLaborClient(): LaborStandardsClient = object : LaborStandardsClient {
            override fun getLaborStandards(
                employerId: EmployerId,
                asOfDate: LocalDate,
                workState: String?,
                homeState: String?,
                localityCodes: List<String>,
            ) = null
        }
    }
}
