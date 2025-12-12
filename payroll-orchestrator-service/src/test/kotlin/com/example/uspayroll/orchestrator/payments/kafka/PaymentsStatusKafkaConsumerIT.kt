package com.example.uspayroll.orchestrator.payments.kafka

import com.example.uspayroll.messaging.events.payments.PaycheckPaymentLifecycleStatus
import com.example.uspayroll.messaging.events.payments.PaycheckPaymentStatusChangedEvent
import com.example.uspayroll.orchestrator.http.PayRunController
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.RequestEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.messaging.support.MessageBuilder
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.TestPropertySource
import java.net.URI
import java.time.Instant

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
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

        // Set up a payrun with one succeeded paycheck.
        val start = rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/finalize"))
                .body(
                    PayRunController.StartFinalizeRequest(
                        payPeriodId = "pp-1",
                        employeeIds = listOf("e-1"),
                        requestedPayRunId = "run-kafka-it-1",
                    )
                ),
            PayRunController.StartFinalizeResponse::class.java,
        )
        assertEquals(HttpStatus.ACCEPTED, start.statusCode)

        val execHeaders = HttpHeaders().apply { set("X-Internal-Token", "dev-internal-token") }
        rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/internal/run-kafka-it-1/execute?batchSize=10"))
                .headers(execHeaders)
                .build(),
            Map::class.java,
        )

        rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/run-kafka-it-1/approve"))
                .build(),
            Map::class.java,
        )

        rest.exchange(
            RequestEntity.post(URI.create("/employers/$employerId/payruns/run-kafka-it-1/payments/initiate"))
                .build(),
            Map::class.java,
        )

        val paycheckId = jdbcTemplate.queryForObject(
            """
            SELECT paycheck_id
            FROM pay_run_item
            WHERE employer_id = ? AND pay_run_id = ? AND employee_id = ?
            """.trimIndent(),
            String::class.java,
            employerId,
            "run-kafka-it-1",
            "e-1",
        )
        assertNotNull(paycheckId)

        // Publish payment status event to embedded Kafka.
        val now = Instant.now()
        val evt = PaycheckPaymentStatusChangedEvent(
            eventId = "evt-kafka-it-1",
            occurredAt = now,
            employerId = employerId,
            payRunId = "run-kafka-it-1",
            paycheckId = paycheckId!!,
            paymentId = "pmt-$paycheckId",
            status = PaycheckPaymentLifecycleStatus.SETTLED,
        )

        val msg = MessageBuilder.withPayload(com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().writeValueAsString(evt))
            .setHeader(KafkaHeaders.TOPIC, "paycheck.payment.status_changed")
            .setHeader(KafkaHeaders.KEY, "$employerId:run-kafka-it-1")
            .setHeader("X-Event-Id", evt.eventId)
            .setHeader("X-Event-Type", "PaycheckPaymentStatusChanged")
            .build()

        kafkaTemplate.send(msg).get()

        // Poll DB until projection updates (listener is async).
        val deadlineMs = System.currentTimeMillis() + 5_000
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
}
