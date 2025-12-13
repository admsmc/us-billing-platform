package com.example.uspayroll.worker.jobs.rabbit

import com.example.uspayroll.messaging.jobs.FinalizePayRunEmployeeJob
import com.example.uspayroll.messaging.jobs.FinalizePayRunJobRouting
import com.example.uspayroll.worker.security.WorkerInternalAuthProperties
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.boot.context.properties.EnableConfigurationProperties
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/internal/jobs/finalize-employee/dlq")
@EnableConfigurationProperties(WorkerInternalAuthProperties::class)
@ConditionalOnExpression("\${worker.jobs.dlq-replayer.enabled:false} and \${worker.jobs.rabbit.enabled:false}")
class DlqReplayController(
    private val finalizeEmployeeProps: FinalizeEmployeeJobsProperties,
    private val rabbitTemplate: RabbitTemplate,
    private val auth: WorkerInternalAuthProperties,
    meterRegistry: MeterRegistry,
) {

    private val replayCounter = meterRegistry.counter("worker.dlq.replay.total", "queue", finalizeEmployeeProps.dlqName)

    @PostMapping("/replay")
    fun replay(
        request: HttpServletRequest,
        @RequestParam(name = "maxMessages", defaultValue = "100") maxMessages: Int,
        @RequestParam(name = "resetAttempt", defaultValue = "true") resetAttempt: Boolean,
    ): ResponseEntity<Map<String, Any?>> {
        val expected = auth.sharedSecret
        val headerName = auth.headerName

        if (expected.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                mapOf(
                    "error" to "unauthorized",
                    "detail" to "worker.internal-auth.shared-secret must be set to use DLQ replay",
                    "headerName" to headerName,
                ),
            )
        }

        val token = request.getHeader(headerName)
        if (token != expected) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                mapOf(
                    "error" to "unauthorized",
                    "headerName" to headerName,
                ),
            )
        }

        val limit = maxMessages.coerceIn(1, 10_000)
        var moved = 0

        while (moved < limit) {
            val msg = rabbitTemplate.receiveAndConvert(finalizeEmployeeProps.dlqName) as? FinalizePayRunEmployeeJob
                ?: break

            val republish = if (resetAttempt) {
                msg.copy(messageId = "msg-${UUID.randomUUID()}", attempt = 1)
            } else {
                msg.copy(messageId = "msg-${UUID.randomUUID()}")
            }

            rabbitTemplate.convertAndSend(
                FinalizePayRunJobRouting.EXCHANGE,
                FinalizePayRunJobRouting.FINALIZE_EMPLOYEE,
                republish,
            )

            moved += 1
        }

        if (moved > 0) replayCounter.increment(moved.toDouble())

        return ResponseEntity.ok(
            mapOf(
                "replayed" to moved,
                "fromQueue" to finalizeEmployeeProps.dlqName,
                "toExchange" to FinalizePayRunJobRouting.EXCHANGE,
                "toRoutingKey" to FinalizePayRunJobRouting.FINALIZE_EMPLOYEE,
            ),
        )
    }
}
