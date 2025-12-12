package com.example.uspayroll.payments

import com.example.uspayroll.messaging.events.payments.PaycheckPaymentRequestedEvent
import com.example.uspayroll.payments.processor.PaymentsProcessor
import com.example.uspayroll.payments.service.PaymentIntakeService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestPropertySource
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant

@SpringBootTest
@TestPropertySource(
    properties = [
        "payments.processor.enabled=true",
        "payments.processor.auto-settle=true",
        "payments.outbox.relay.enabled=false",
        "payments.kafka.enabled=false",
    ],
)
class PaymentsProcessorIT(
    private val intake: PaymentIntakeService,
    private val processor: PaymentsProcessor,
    private val jdbcTemplate: JdbcTemplate,
) {
    @Test
    fun `processor submits and settles payments and emits status events`() {
        val employerId = "emp-1"
        val payRunId = "run-1"
        val paycheckId = "chk-2"

        val evt = PaycheckPaymentRequestedEvent(
            eventId = "paycheck-payment-requested:$employerId:$paycheckId",
            occurredAt = Instant.now(),
            employerId = employerId,
            payRunId = payRunId,
            payPeriodId = "pp-1",
            employeeId = "e-1",
            paycheckId = paycheckId,
            currency = "USD",
            netCents = 2_000_00L,
        )

        intake.handlePaymentRequested(evt)

        processor.tickOnce(now = Instant.parse("2025-01-01T00:00:00Z"))

        val status = jdbcTemplate.queryForObject(
            "SELECT status FROM paycheck_payment WHERE employer_id = ? AND paycheck_id = ?",
            String::class.java,
            employerId,
            paycheckId,
        )
        assertEquals("SETTLED", status)

        val submittedEventId = "paycheck-payment-status-changed:$employerId:$paycheckId:SUBMITTED"
        val settledEventId = "paycheck-payment-status-changed:$employerId:$paycheckId:SETTLED"

        val submittedCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM outbox_event WHERE event_id = ?",
            Long::class.java,
            submittedEventId,
        ) ?: 0L
        val settledCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM outbox_event WHERE event_id = ?",
            Long::class.java,
            settledEventId,
        ) ?: 0L

        assertEquals(1L, submittedCount)
        assertEquals(1L, settledCount)
    }
}
