package com.example.usbilling.worker.jobs.rabbit

import com.example.usbilling.messaging.jobs.FinalizePayRunEmployeeJob
import com.example.usbilling.messaging.jobs.FinalizePayRunJobRouting
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito
import org.springframework.amqp.rabbit.core.RabbitTemplate

class DlqReplayControllerTest {

    @Test
    fun `replay moves messages from dlq to main queue`() {
        val props = FinalizeEmployeeJobsProperties(dlqName = "dlq")
        val rabbit = Mockito.mock(RabbitTemplate::class.java)
        val controller = DlqReplayController(props, rabbit, SimpleMeterRegistry())

        val request = Mockito.mock(HttpServletRequest::class.java)

        val msg = FinalizePayRunEmployeeJob(
            messageId = "old",
            employerId = "EMP",
            payRunId = "PR",
            payPeriodId = "PP",
            runType = "REGULAR",
            runSequence = 1,
            employeeId = "EE",
            paycheckId = "CHK-1",
            earningOverrides = emptyList(),
            attempt = 7,
        )

        Mockito.`when`(rabbit.receiveAndConvert("dlq"))
            .thenReturn(msg)
            .thenReturn(null)

        val resp = controller.replay(request, maxMessages = 10, resetAttempt = true)
        assertEquals(200, resp.statusCode.value())
        assertEquals(1, resp.body?.replayed)

        val captor = ArgumentCaptor.forClass(FinalizePayRunEmployeeJob::class.java)
        Mockito.verify(rabbit).convertAndSend(eq(FinalizePayRunJobRouting.EXCHANGE), eq(FinalizePayRunJobRouting.FINALIZE_EMPLOYEE), captor.capture())

        assertEquals("EMP", captor.value.employerId)
        assertEquals("PR", captor.value.payRunId)
        assertEquals("EE", captor.value.employeeId)
        assertEquals(1, captor.value.attempt)
    }
}
