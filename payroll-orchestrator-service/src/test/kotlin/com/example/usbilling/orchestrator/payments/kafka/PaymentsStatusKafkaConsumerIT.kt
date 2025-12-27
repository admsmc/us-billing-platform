package com.example.usbilling.orchestrator.payments.kafka

import com.example.usbilling.messaging.events.payments.PaycheckPaymentLifecycleStatus
import com.example.usbilling.messaging.events.payments.PaycheckPaymentStatusChangedEvent
import com.example.usbilling.orchestrator.http.PayRunController
import com.example.usbilling.orchestrator.support.InternalAuthTestSupport
import com.example.usbilling.orchestrator.support.StubClientsTestConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.http.RequestEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.messaging.support.MessageBuilder
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.TestConstructor
import org.springframework.test.context.TestPropertySource
import java.net.URI
import java.time.Instant
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(StubClientsTestConfig::class)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@EmbeddedKafka(
    partitions = 1,
    topics = ["paycheck.payment.status_changed"],
)
@TestPropertySource(
    properties = [
        "orchestrator.payments.enabled=true",
        "orchestrator.payments.consumer-name=payroll-orchestrator-service-it",
        "orchestrator.payments.group-id=payroll-orchestrator-payments-it",
        "orchestrator.payments.payment-status-changed-topic=paycheck.payment.status_changed",
        "orchestrator.internal-auth.jwt-keys.k1=dev-internal-token",
        "orchestrator.internal-auth.jwt-default-kid=k1",
        "orchestrator.payrun.execute.enabled=true",
        "spring.kafka.bootstrap-servers=\${spring.embedded.kafka.brokers}",
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "spring.kafka.consumer.enable-auto-commit=false",
    ],
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class PaymentsStatusKafkaConsumerIT(
    private val rest: TestRestTemplate,
    private val jdbcTemplate: JdbcTemplate,
    private val kafkaTemplate: KafkaTemplate<String, String>,
) {
    @Test
    fun `listener consumes payment status changed event and updates DB projection`() {
        val employerId = "emp-1"

        val payRunId = "run-kafka-it-${UUID.randomUUID()}"
        // Avoid business-key collisions across concurrently-running Spring contexts.
        val runSequence = ((UUID.randomUUID().mostSignificantBits ushr 1) % 1_000_000L).toInt() + 1

        // Set up a payrun with one succeeded paycheck.
        val start = rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/finalize"))
                .body(
                    PayRunController.StartFinalizeRequest(
                        payPeriodId = "pp-1",
                        employeeIds = listOf("e-1"),
                        runSequence = runSequence,
                        requestedPayRunId = payRunId,
                    ),
                ),
            PayRunController.StartFinalizeResponse::class.java,
        )
        assertEquals(HttpStatus.ACCEPTED, start.statusCode)

        val execHeaders = InternalAuthTestSupport.internalAuthHeaders()
        rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/internal/$payRunId/execute?batchSize=10"))
                .headers(execHeaders)
                .build(),
            Map::class.java,
        )

        waitForTerminalFinalize(employerId, payRunId)

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

        val paycheckId = waitForBillId(employerId, payRunId, "e-1")
        assertNotNull(paycheckId)

        // Publish payment status event to embedded Kafka.
        val now = Instant.now()
        val evt = PaycheckPaymentStatusChangedEvent(
            eventId = "evt-kafka-it-1",
            occurredAt = now,
            employerId = employerId,
            payRunId = payRunId,
            paycheckId = paycheckId!!,
            paymentId = "pmt-$paycheckId",
            status = PaycheckPaymentLifecycleStatus.SETTLED,
        )

        val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().findAndRegisterModules()
        val msg = MessageBuilder.withPayload(mapper.writeValueAsString(evt))
            .setHeader(KafkaHeaders.TOPIC, "paycheck.payment.status_changed")
            .setHeader(KafkaHeaders.KEY, "$employerId:$payRunId")
            .setHeader("X-Event-Id", evt.eventId)
            .setHeader("X-Event-Type", "PaycheckPaymentStatusChanged")
            .build()

        kafkaTemplate.send(msg).get()

        // Poll DB until projection updates (listener is async).
        val deadlineMs = System.currentTimeMillis() + 45_000
        while (System.currentTimeMillis() < deadlineMs) {
            val status = jdbcTemplate.queryForObject(
                "SELECT payment_status FROM paycheck WHERE employer_id = ? AND paycheck_id = ?",
                String::class.java,
                employerId,
                paycheckId,
            )
            if (status == "PAID") {
                return
            }
            Thread.sleep(100)
        }

        throw AssertionError("Timed out waiting for paycheck payment_status=PAID")
    }

    private fun waitForTerminalFinalize(employerId: String, payRunId: String) {
        // When running the full multi-module build, containers/DB can be slower; keep this conservative.
        val deadlineMs = System.currentTimeMillis() + 120_000
        while (System.currentTimeMillis() < deadlineMs) {
            val status = jdbcTemplate.query(
                "SELECT status FROM pay_run WHERE employer_id = ? AND pay_run_id = ?",
                { rs, _ -> rs.getString("status") },
                employerId,
                payRunId,
            ).firstOrNull()

            if (status == "FINALIZED" || status == "PARTIALLY_FINALIZED" || status == "FAILED") {
                return
            }
            Thread.sleep(100)
        }

        throw AssertionError("Timed out waiting for terminal pay_run.status")
    }

    private fun waitForBillId(employerId: String, payRunId: String, employeeId: String): String? {
        val deadlineMs = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadlineMs) {
            val paycheckId = jdbcTemplate.query(
                """
                SELECT paycheck_id
                FROM pay_run_item
                WHERE employer_id = ? AND pay_run_id = ? AND employee_id = ?
                """.trimIndent(),
                { rs, _ -> rs.getString("paycheck_id") },
                employerId,
                payRunId,
                employeeId,
            ).firstOrNull()

            if (!paycheckId.isNullOrBlank()) {
                return paycheckId
            }

            Thread.sleep(100)
        }

        return null
    }
}
