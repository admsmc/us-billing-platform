package com.example.uspayroll.worker.jobs.rabbit

import com.example.uspayroll.messaging.jobs.FinalizePayRunEmployeeJob
import com.example.uspayroll.messaging.jobs.FinalizePayRunJobRouting
import com.example.uspayroll.shared.EmployerId
import com.example.uspayroll.worker.client.FinalizeEmployeeItemResponse
import com.example.uspayroll.worker.client.OrchestratorClient
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito
import org.springframework.amqp.rabbit.core.RabbitTemplate

class RabbitFinalizeEmployeeItemConsumerTest {

    @Test
    fun `terminal success does not republish`() {
        val props = FinalizeEmployeeJobsProperties(enabled = true, maxAttempts = 3)
        val orchestrator = Mockito.mock(OrchestratorClient::class.java)
        val rabbit = Mockito.mock(RabbitTemplate::class.java)
        val meters = SimpleMeterRegistry()

        Mockito.`when`(
            orchestrator.finalizeEmployeeItem(
                EmployerId("EMP"),
                "PR",
                "EE",
            ),
        ).thenReturn(
            FinalizeEmployeeItemResponse(
                employerId = "EMP",
                payRunId = "PR",
                employeeId = "EE",
                itemStatus = "SUCCEEDED",
                attemptCount = 1,
                paycheckId = "CHK-1",
                retryable = false,
                error = null,
            ),
        )

        val consumer = RabbitFinalizeEmployeeItemConsumer(props, orchestrator, rabbit, meters)
        consumer.onJob(
            FinalizePayRunEmployeeJob(
                messageId = "msg-1",
                employerId = "EMP",
                payRunId = "PR",
                employeeId = "EE",
                attempt = 1,
            ),
        )

        Mockito.verify(rabbit, Mockito.never()).convertAndSend(anyString(), anyString(), any(Any::class.java))

        val succeeded = meters
            .find("worker.payrun.finalize_employee.total")
            .tag("outcome", "succeeded")
            .counter()
            ?.count() ?: 0.0
        assertEquals(1.0, succeeded)
    }

    @Test
    fun `retryable result republishes to retry routing key and increments attempt`() {
        val props = FinalizeEmployeeJobsProperties(enabled = true, maxAttempts = 5)
        val orchestrator = Mockito.mock(OrchestratorClient::class.java)
        val rabbit = Mockito.mock(RabbitTemplate::class.java)
        val meters = SimpleMeterRegistry()

        Mockito.`when`(
            orchestrator.finalizeEmployeeItem(
                EmployerId("EMP"),
                "PR",
                "EE",
            ),
        ).thenReturn(
            FinalizeEmployeeItemResponse(
                employerId = "EMP",
                payRunId = "PR",
                employeeId = "EE",
                itemStatus = "FAILED",
                attemptCount = 1,
                paycheckId = null,
                retryable = true,
                error = "transient",
            ),
        )

        val consumer = RabbitFinalizeEmployeeItemConsumer(props, orchestrator, rabbit, meters)

        val job = FinalizePayRunEmployeeJob(
            messageId = "msg-1",
            employerId = "EMP",
            payRunId = "PR",
            employeeId = "EE",
            attempt = 1,
        )

        consumer.onJob(job)

        val captor = ArgumentCaptor.forClass(FinalizePayRunEmployeeJob::class.java)
        Mockito.verify(rabbit).convertAndSend(eq(FinalizePayRunJobRouting.EXCHANGE), eq(FinalizePayRunJobRouting.RETRY_30S), captor.capture())
        assertEquals(2, captor.value.attempt)

        val retryEnqueued = meters
            .find("worker.payrun.finalize_employee.total")
            .tag("outcome", "retry_enqueued")
            .counter()
            ?.count() ?: 0.0
        assertEquals(1.0, retryEnqueued)
    }

    @Test
    fun `attempts exhausted republishes to dlq`() {
        val props = FinalizeEmployeeJobsProperties(enabled = true, maxAttempts = 2)
        val orchestrator = Mockito.mock(OrchestratorClient::class.java)
        val rabbit = Mockito.mock(RabbitTemplate::class.java)
        val meters = SimpleMeterRegistry()

        Mockito.`when`(
            orchestrator.finalizeEmployeeItem(
                EmployerId("EMP"),
                "PR",
                "EE",
            ),
        ).thenReturn(
            FinalizeEmployeeItemResponse(
                employerId = "EMP",
                payRunId = "PR",
                employeeId = "EE",
                itemStatus = "FAILED",
                attemptCount = 2,
                paycheckId = null,
                retryable = true,
                error = "still failing",
            ),
        )

        val consumer = RabbitFinalizeEmployeeItemConsumer(props, orchestrator, rabbit, meters)

        val job = FinalizePayRunEmployeeJob(
            messageId = "msg-1",
            employerId = "EMP",
            payRunId = "PR",
            employeeId = "EE",
            attempt = 2,
        )

        consumer.onJob(job)

        val captor = ArgumentCaptor.forClass(FinalizePayRunEmployeeJob::class.java)
        Mockito.verify(rabbit).convertAndSend(eq(FinalizePayRunJobRouting.EXCHANGE), eq(FinalizePayRunJobRouting.DLQ), captor.capture())
        assertEquals(3, captor.value.attempt)

        val dlq = meters
            .find("worker.payrun.finalize_employee.total")
            .tag("outcome", "dlq")
            .counter()
            ?.count() ?: 0.0
        assertEquals(1.0, dlq)
    }
}
