package com.example.uspayroll.payments

import com.example.uspayroll.messaging.events.payments.PaycheckPaymentRequestedEvent
import com.example.uspayroll.payments.service.PaymentIntakeService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestConstructor
import java.time.Instant

@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@org.springframework.test.context.TestPropertySource(
    properties = [
        "spring.task.scheduling.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:payments_intake_it;DB_CLOSE_DELAY=-1",
        "payments.kafka.enabled=false",
        "payments.outbox.relay.enabled=false",
    ],
)
class PaymentIntakeIT(
    private val intake: PaymentIntakeService,
    private val jdbcTemplate: JdbcTemplate,
) {
    @Test
    fun `handlePaymentRequested is idempotent per paycheck and emits CREATED once`() {
        val employerId = "emp-1"
        val payRunId = "run-1"
        val paycheckId = "chk-1"

        val evt = PaycheckPaymentRequestedEvent(
            eventId = "paycheck-payment-requested:$employerId:$paycheckId",
            occurredAt = Instant.now(),
            employerId = employerId,
            payRunId = payRunId,
            payPeriodId = "pp-1",
            employeeId = "e-1",
            paycheckId = paycheckId,
            currency = "USD",
            netCents = 1_000_00L,
        )

        intake.handlePaymentRequested(evt)
        intake.handlePaymentRequested(evt)

        val paymentCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM paycheck_payment WHERE employer_id = ? AND paycheck_id = ?",
            Long::class.java,
            employerId,
            paycheckId,
        ) ?: 0L
        assertEquals(1L, paymentCount)

        val createdEventId = "paycheck-payment-status-changed:$employerId:$paycheckId:CREATED"
        val outboxCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM outbox_event WHERE event_id = ?",
            Long::class.java,
            createdEventId,
        ) ?: 0L
        assertEquals(1L, outboxCount)
    }
}
