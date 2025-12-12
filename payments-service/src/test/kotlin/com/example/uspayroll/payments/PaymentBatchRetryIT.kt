package com.example.uspayroll.payments

import com.example.uspayroll.messaging.events.payments.PaycheckPaymentRequestedEvent
import com.example.uspayroll.payments.persistence.PaymentBatchRepository
import com.example.uspayroll.payments.processor.PaymentsProcessor
import com.example.uspayroll.payments.service.PaymentIntakeService
import com.example.uspayroll.payments.sweeper.PaymentBatchSweeper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestConstructor
import org.springframework.test.context.TestPropertySource
import java.time.Instant

@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@TestPropertySource(
    properties = [
        "spring.task.scheduling.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:payments_batch_retry_it;DB_CLOSE_DELAY=-1",

        "payments.processor.enabled=true",
        "payments.processor.auto-settle=true",
        "payments.processor.fail-if-net-cents-equals=200000",
        "payments.kafka.enabled=false",
        "payments.outbox.relay.enabled=false",

        "payments.batch-sweeper.enabled=true",
        "payments.batch-sweeper.retry-base-millis=1000",
        "payments.batch-sweeper.retry-max-millis=1000",
        "payments.batch-sweeper.max-batch-attempts=3",
        "payments.batch-sweeper.max-payment-attempts=3",
    ],
)
class PaymentBatchRetryIT(
    private val intake: PaymentIntakeService,
    private val processor: PaymentsProcessor,
    private val sweeper: PaymentBatchSweeper,
    private val batches: PaymentBatchRepository,
    private val jdbcTemplate: JdbcTemplate,
) {
    @Test
    fun `partially completed batch is retried and can complete`() {
        val employerId = "emp-1"
        val payRunId = "run-retry-1"

        intake.handlePaymentRequested(
            PaycheckPaymentRequestedEvent(
                eventId = "paycheck-payment-requested:$employerId:chk-ok",
                occurredAt = Instant.now(),
                employerId = employerId,
                payRunId = payRunId,
                payPeriodId = "pp-1",
                employeeId = "e-1",
                paycheckId = "chk-ok",
                currency = "USD",
                netCents = 100_000L,
            ),
        )

        intake.handlePaymentRequested(
            PaycheckPaymentRequestedEvent(
                eventId = "paycheck-payment-requested:$employerId:chk-fail",
                occurredAt = Instant.now(),
                employerId = employerId,
                payRunId = payRunId,
                payPeriodId = "pp-1",
                employeeId = "e-2",
                paycheckId = "chk-fail",
                currency = "USD",
                netCents = 200_000L,
            ),
        )

        val t0 = Instant.parse("2025-01-01T00:00:00Z")
        processor.tickOnce(now = t0)

        val batchId = batches.findBatchIdForPayRun(employerId, payRunId)!!
        val b1 = batches.reconcileBatch(employerId, batchId)!!
        assertEquals("PARTIALLY_COMPLETED", b1.status.name)

        // First sweep schedules retry.
        sweeper.tickOnce(now = t0)

        // Advance time past retry delay and sweep again to reopen failed payments.
        val t1 = t0.plusMillis(1500)
        sweeper.tickOnce(now = t1)

        // Next processor run should settle the previously-failed payment (fail hook only fails on attempts==0).
        processor.tickOnce(now = t1)

        val b2 = batches.reconcileBatch(employerId, batchId)!!
        assertEquals("COMPLETED", b2.status.name)
        assertEquals(2, b2.totalPayments)
        assertEquals(2, b2.settledPayments)
        assertEquals(0, b2.failedPayments)

        val batchEventCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM outbox_event WHERE topic = 'payment.batch.status_changed'",
            Long::class.java,
        ) ?: 0L
        // At least one batch status event emitted during the lifecycle.
        org.junit.jupiter.api.Assertions.assertTrue(batchEventCount >= 1L)

        val terminalCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM outbox_event WHERE topic = 'payment.batch.terminal' AND event_type = 'PaymentBatchTerminal'",
            Long::class.java,
        ) ?: 0L
        org.junit.jupiter.api.Assertions.assertEquals(1L, terminalCount)
    }
}
