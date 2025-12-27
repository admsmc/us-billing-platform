package com.example.usbilling.worker.jobs.rabbit

import com.example.usbilling.shared.UtilityId
import com.example.usbilling.worker.jobs.FinalizePayRunJob
import com.example.usbilling.worker.jobs.FinalizePayRunJobQueue
import com.example.usbilling.worker.jobs.FinalizePayRunJobStore
import com.example.usbilling.worker.orchestrator.OrchestratorPayRunJobRunner
import org.slf4j.LoggerFactory
import org.springframework.amqp.core.Queue
import org.springframework.amqp.rabbit.annotation.RabbitListener
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component

@ConfigurationProperties(prefix = "worker.jobs.rabbit")
data class RabbitJobsProperties(
    var enabled: Boolean = false,
    var queueName: String = "finalize-payrun-jobs",
)

@Configuration
@EnableConfigurationProperties(RabbitJobsProperties::class)
class RabbitFinalizePayRunJobQueueConfig {

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnExpression(
        "\${worker.jobs.legacy-payrun.enabled:false} and \${worker.jobs.rabbit.enabled:false}",
    )
    fun finalizePayRunJobsQueue(props: RabbitJobsProperties): Queue = Queue(props.queueName, true)

    @Bean
    @Primary
    @org.springframework.boot.autoconfigure.condition.ConditionalOnExpression(
        "\${worker.jobs.legacy-payrun.enabled:false} and \${worker.jobs.rabbit.enabled:false}",
    )
    fun rabbitFinalizePayRunJobQueue(rabbitTemplate: RabbitTemplate, props: RabbitJobsProperties, rabbitMessageConverter: Jackson2JsonMessageConverter): FinalizePayRunJobQueue {
        rabbitTemplate.messageConverter = rabbitMessageConverter
        return RabbitFinalizePayRunJobQueue(rabbitTemplate, props)
    }
}

class RabbitFinalizePayRunJobQueue(
    private val rabbitTemplate: RabbitTemplate,
    private val props: RabbitJobsProperties,
) : FinalizePayRunJobQueue {

    override fun enqueue(job: FinalizePayRunJob) {
        rabbitTemplate.convertAndSend(props.queueName, job)
    }

    override fun tryDequeue(): FinalizePayRunJob? {
        // Not used for Rabbit; consumption is via @RabbitListener.
        return null
    }
}

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnExpression(
    "\${worker.jobs.legacy-payrun.enabled:false} and \${worker.jobs.rabbit.enabled:false}",
)
class RabbitFinalizePayRunJobConsumer(
    private val store: FinalizePayRunJobStore,
    private val runner: OrchestratorPayRunJobRunner,
) {

    private val logger = LoggerFactory.getLogger(RabbitFinalizePayRunJobConsumer::class.java)

    @RabbitListener(queues = ["\${worker.jobs.rabbit.queue-name:finalize-payrun-jobs}"])
    fun onJob(job: FinalizePayRunJob) {
        store.markRunning(job.jobId)

        try {
            val result = runner.runFinalizeJob(
                employerId = UtilityId(job.employerId),
                payPeriodId = job.payPeriodId,
                employeeIds = job.employeeIds,
                requestedPayRunId = job.requestedPayRunId,
                idempotencyKey = job.idempotencyKey,
            )

            store.markSucceeded(
                jobId = job.jobId,
                payRunId = result.payRunId,
                finalStatus = result.finalStatus,
            )
        } catch (t: Throwable) {
            logger.error("job.finalize_payrun.failed jobId={} employer={} payPeriodId={} err={}", job.jobId, job.employerId, job.payPeriodId, t.message, t)
            store.markFailed(job.jobId, t.message ?: t::class.java.name)
        }
    }
}
