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
import org.springframework.test.context.TestPropertySource
import java.time.Instant

@SpringBootTest
@TestPropertySource(
    properties = [
        "payments.processor.enabled=true",
        "payments.processor.auto-settle=true",
        "payments.processor.fail-if-net-cents-equals=200000",
        "payments.kafka.enabled=false",
        "payments.outbox.relay.enabled=false",

        "payments.batch-sweeper.enabled=true",
        // fail immediately after first partial detection
        "payments.batch-sweeper.max-batch-attempts=0",
    ],
)
class PaymentBatchTerminalFailedIT(
    private val intake: PaymentIntakeService,
    private val processor: PaymentsProcessor,
    private val sweeper: PaymentBatchSweeper,
    private val batches: PaymentBatchRepository,
    private val jdbcTemplate: JdbcTemplate,
) {
    @Test
    fun `failed terminal event is emitted when batch is marked failed`() {
        val employerId = "emp-1"
        val payRunId = "run-fail-terminal-1"

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
            )
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
            )
        )

        val t0 = Instant.parse("2025-01-01T00:00:00Z")
        processor.tickOnce(now = t0)

        val batchId = batches.findBatchIdForPayRun(employerId, payRunId)!!
        sweeper.tickOnce(now = t0)

        val batch = batches.findByBatchId(employerId, batchId)!!
        assertEquals("FAILED", batch.status.name)

        val terminalCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM outbox_event WHERE topic = 'payment.batch.terminal' AND event_type = 'PaymentBatchTerminal'",
            Long::class.java,
        ) ?: 0L
        assertEquals(1L, terminalCount)
    }
}
