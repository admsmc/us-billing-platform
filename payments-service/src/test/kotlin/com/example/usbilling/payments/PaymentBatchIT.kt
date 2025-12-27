package com.example.usbilling.payments

import com.example.usbilling.messaging.events.payments.PaycheckPaymentRequestedEvent
import com.example.usbilling.payments.persistence.PaymentBatchRepository
import com.example.usbilling.payments.processor.PaymentsProcessor
import com.example.usbilling.payments.service.PaymentIntakeService
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
        "spring.datasource.url=jdbc:h2:mem:payments_batch_it;DB_CLOSE_DELAY=-1",

        "payments.processor.enabled=true",
        "payments.provider.type=sandbox",
        "payments.provider.sandbox.auto-settle=true",
        "payments.provider.sandbox.fail-if-net-cents-equals=200000",
        "payments.kafka.enabled=false",
        "payments.outbox.relay.enabled=false",
    ],
)
class PaymentBatchIT(
    private val intake: PaymentIntakeService,
    private val processor: PaymentsProcessor,
    private val batches: PaymentBatchRepository,
    private val jdbcTemplate: JdbcTemplate,
) {
    @Test
    fun `batch reflects partially completed when some payments fail`() {
        val employerId = "emp-1"
        val payRunId = "run-batch-1"

        val ok = PaycheckPaymentRequestedEvent(
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

        val fail = PaycheckPaymentRequestedEvent(
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

        intake.handlePaymentRequested(ok)
        intake.handlePaymentRequested(fail)

        processor.tickOnce(now = Instant.parse("2025-01-01T00:00:00Z"))

        val batchId = batches.findBatchIdForPayRun(employerId, payRunId)!!

        // Reconcile in case processor didn't touch the second row this tick.
        val reconciled = batches.reconcileBatch(employerId, batchId)!!

        assertEquals(2, reconciled.totalPayments)
        assertEquals(1, reconciled.settledPayments)
        assertEquals(1, reconciled.failedPayments)
        assertEquals("PARTIALLY_COMPLETED", reconciled.status.name)

        val statuses = jdbcTemplate.queryForList(
            "SELECT paycheck_id, status FROM paycheck_payment WHERE employer_id = ? AND pay_run_id = ? ORDER BY paycheck_id",
            employerId,
            payRunId,
        )

        assertEquals(listOf("chk-fail", "chk-ok"), statuses.map { it["PAYCHECK_ID"].toString() })

        val batchProvider = jdbcTemplate.queryForObject(
            "SELECT provider FROM payment_batch WHERE employer_id = ? AND batch_id = ?",
            String::class.java,
            employerId,
            batchId,
        )
        assertEquals("SANDBOX", batchProvider)

        val batchRef = jdbcTemplate.queryForObject(
            "SELECT provider_batch_ref FROM payment_batch WHERE employer_id = ? AND batch_id = ?",
            String::class.java,
            employerId,
            batchId,
        )
        // Sandbox returns deterministic batch ref and processor persists it.
        org.junit.jupiter.api.Assertions.assertTrue(batchRef?.startsWith("sandbox-batch:") == true)

        val paymentRefs = jdbcTemplate.queryForList(
            "SELECT paycheck_id, provider_payment_ref FROM paycheck_payment WHERE employer_id = ? AND pay_run_id = ? ORDER BY paycheck_id",
            employerId,
            payRunId,
        )
        org.junit.jupiter.api.Assertions.assertTrue(paymentRefs.all { it["PROVIDER_PAYMENT_REF"] != null })
    }
}
