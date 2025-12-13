package com.example.uspayroll.worker.jobs.rabbit

import com.example.uspayroll.messaging.jobs.FinalizePayRunEmployeeJob
import com.example.uspayroll.messaging.jobs.FinalizePayRunJobRouting
import com.example.uspayroll.shared.EmployerId
import com.example.uspayroll.worker.client.OrchestratorClient
import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.springframework.amqp.core.Binding
import org.springframework.amqp.core.BindingBuilder
import org.springframework.amqp.core.Queue
import org.springframework.amqp.core.QueueBuilder
import org.springframework.amqp.core.TopicExchange
import org.springframework.amqp.rabbit.annotation.RabbitListener
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component

@ConfigurationProperties(prefix = "worker.jobs.finalize-employee")
data class FinalizeEmployeeJobsProperties(
    var enabled: Boolean = true,
    /** Queue name for main per-employee finalize jobs. */
    var queueName: String = "payrun-finalize-employee-jobs",
    /** DLQ name. */
    var dlqName: String = "payrun-finalize-employee-jobs-dlq",
    /** Maximum total attempts (attempt 1 + retries). */
    var maxAttempts: Int = 8,
)

@Configuration
@EnableConfigurationProperties(FinalizeEmployeeJobsProperties::class)
class RabbitFinalizeEmployeeItemQueueConfig {

    @Bean
    fun payrunJobsExchange(): TopicExchange = TopicExchange(FinalizePayRunJobRouting.EXCHANGE, true, false)

    @Bean
    @ConditionalOnProperty(prefix = "worker.jobs.rabbit", name = ["enabled"], havingValue = "true")
    fun finalizeEmployeeMainQueue(props: FinalizeEmployeeJobsProperties): Queue = Queue(props.queueName, true)

    @Bean
    @ConditionalOnProperty(prefix = "worker.jobs.rabbit", name = ["enabled"], havingValue = "true")
    fun finalizeEmployeeDlq(props: FinalizeEmployeeJobsProperties): Queue = Queue(props.dlqName, true)

    @Bean
    @ConditionalOnProperty(prefix = "worker.jobs.rabbit", name = ["enabled"], havingValue = "true")
    fun finalizeEmployeeMainBinding(exchange: TopicExchange, finalizeEmployeeMainQueue: Queue): Binding =
        BindingBuilder.bind(finalizeEmployeeMainQueue).to(exchange).with(FinalizePayRunJobRouting.FINALIZE_EMPLOYEE)

    @Bean
    @ConditionalOnProperty(prefix = "worker.jobs.rabbit", name = ["enabled"], havingValue = "true")
    fun finalizeEmployeeDlqBinding(exchange: TopicExchange, finalizeEmployeeDlq: Queue): Binding =
        BindingBuilder.bind(finalizeEmployeeDlq).to(exchange).with(FinalizePayRunJobRouting.DLQ)

    private fun retryQueue(name: String, ttlMillis: Int, exchange: TopicExchange, deadLetterRoutingKey: String): Queue =
        QueueBuilder.durable(name)
            .withArgument("x-message-ttl", ttlMillis)
            .withArgument("x-dead-letter-exchange", exchange.name)
            .withArgument("x-dead-letter-routing-key", deadLetterRoutingKey)
            .build()

    @Bean
    @ConditionalOnProperty(prefix = "worker.jobs.rabbit", name = ["enabled"], havingValue = "true")
    fun finalizeEmployeeRetry30s(exchange: TopicExchange): Queue = retryQueue("payrun-finalize-employee-retry-30s", 30_000, exchange, FinalizePayRunJobRouting.FINALIZE_EMPLOYEE)

    @Bean
    @ConditionalOnProperty(prefix = "worker.jobs.rabbit", name = ["enabled"], havingValue = "true")
    fun finalizeEmployeeRetry1m(exchange: TopicExchange): Queue = retryQueue("payrun-finalize-employee-retry-1m", 60_000, exchange, FinalizePayRunJobRouting.FINALIZE_EMPLOYEE)

    @Bean
    @ConditionalOnProperty(prefix = "worker.jobs.rabbit", name = ["enabled"], havingValue = "true")
    fun finalizeEmployeeRetry2m(exchange: TopicExchange): Queue = retryQueue("payrun-finalize-employee-retry-2m", 120_000, exchange, FinalizePayRunJobRouting.FINALIZE_EMPLOYEE)

    @Bean
    @ConditionalOnProperty(prefix = "worker.jobs.rabbit", name = ["enabled"], havingValue = "true")
    fun finalizeEmployeeRetry5m(exchange: TopicExchange): Queue = retryQueue("payrun-finalize-employee-retry-5m", 300_000, exchange, FinalizePayRunJobRouting.FINALIZE_EMPLOYEE)

    @Bean
    @ConditionalOnProperty(prefix = "worker.jobs.rabbit", name = ["enabled"], havingValue = "true")
    fun finalizeEmployeeRetry10m(exchange: TopicExchange): Queue = retryQueue("payrun-finalize-employee-retry-10m", 600_000, exchange, FinalizePayRunJobRouting.FINALIZE_EMPLOYEE)

    @Bean
    @ConditionalOnProperty(prefix = "worker.jobs.rabbit", name = ["enabled"], havingValue = "true")
    fun finalizeEmployeeRetry20m(exchange: TopicExchange): Queue = retryQueue("payrun-finalize-employee-retry-20m", 1_200_000, exchange, FinalizePayRunJobRouting.FINALIZE_EMPLOYEE)

    @Bean
    @ConditionalOnProperty(prefix = "worker.jobs.rabbit", name = ["enabled"], havingValue = "true")
    fun finalizeEmployeeRetry40m(exchange: TopicExchange): Queue = retryQueue("payrun-finalize-employee-retry-40m", 2_400_000, exchange, FinalizePayRunJobRouting.FINALIZE_EMPLOYEE)

    @Bean
    @ConditionalOnProperty(prefix = "worker.jobs.rabbit", name = ["enabled"], havingValue = "true")
    fun retry30sBinding(exchange: TopicExchange, finalizeEmployeeRetry30s: Queue): Binding =
        BindingBuilder.bind(finalizeEmployeeRetry30s).to(exchange).with(FinalizePayRunJobRouting.RETRY_30S)

    @Bean
    @ConditionalOnProperty(prefix = "worker.jobs.rabbit", name = ["enabled"], havingValue = "true")
    fun retry1mBinding(exchange: TopicExchange, finalizeEmployeeRetry1m: Queue): Binding =
        BindingBuilder.bind(finalizeEmployeeRetry1m).to(exchange).with(FinalizePayRunJobRouting.RETRY_1M)

    @Bean
    @ConditionalOnProperty(prefix = "worker.jobs.rabbit", name = ["enabled"], havingValue = "true")
    fun retry2mBinding(exchange: TopicExchange, finalizeEmployeeRetry2m: Queue): Binding =
        BindingBuilder.bind(finalizeEmployeeRetry2m).to(exchange).with(FinalizePayRunJobRouting.RETRY_2M)

    @Bean
    @ConditionalOnProperty(prefix = "worker.jobs.rabbit", name = ["enabled"], havingValue = "true")
    fun retry5mBinding(exchange: TopicExchange, finalizeEmployeeRetry5m: Queue): Binding =
        BindingBuilder.bind(finalizeEmployeeRetry5m).to(exchange).with(FinalizePayRunJobRouting.RETRY_5M)

    @Bean
    @ConditionalOnProperty(prefix = "worker.jobs.rabbit", name = ["enabled"], havingValue = "true")
    fun retry10mBinding(exchange: TopicExchange, finalizeEmployeeRetry10m: Queue): Binding =
        BindingBuilder.bind(finalizeEmployeeRetry10m).to(exchange).with(FinalizePayRunJobRouting.RETRY_10M)

    @Bean
    @ConditionalOnProperty(prefix = "worker.jobs.rabbit", name = ["enabled"], havingValue = "true")
    fun retry20mBinding(exchange: TopicExchange, finalizeEmployeeRetry20m: Queue): Binding =
        BindingBuilder.bind(finalizeEmployeeRetry20m).to(exchange).with(FinalizePayRunJobRouting.RETRY_20M)

    @Bean
    @ConditionalOnProperty(prefix = "worker.jobs.rabbit", name = ["enabled"], havingValue = "true")
    fun retry40mBinding(exchange: TopicExchange, finalizeEmployeeRetry40m: Queue): Binding =
        BindingBuilder.bind(finalizeEmployeeRetry40m).to(exchange).with(FinalizePayRunJobRouting.RETRY_40M)

    @Bean
    @ConditionalOnProperty(prefix = "worker.jobs.rabbit", name = ["enabled"], havingValue = "true")
    fun rabbitMessageConverter(objectMapper: ObjectMapper): Jackson2JsonMessageConverter =
        Jackson2JsonMessageConverter(objectMapper)
}

@Component
@ConditionalOnProperty(prefix = "worker.jobs.rabbit", name = ["enabled"], havingValue = "true")
class RabbitFinalizeEmployeeItemConsumer(
    private val props: FinalizeEmployeeJobsProperties,
    private val orchestratorClient: OrchestratorClient,
    private val rabbitTemplate: RabbitTemplate,
    meterRegistry: MeterRegistry,
) {

    private val logger = LoggerFactory.getLogger(RabbitFinalizeEmployeeItemConsumer::class.java)

    private val finalizeTimer: Timer = meterRegistry.timer("worker.payrun.finalize_employee.duration")
    private val outcomeCounter = meterRegistry.counter("worker.payrun.finalize_employee.total", "outcome", "unknown")
    private val succeededCounter = meterRegistry.counter("worker.payrun.finalize_employee.total", "outcome", "succeeded")
    private val failedTerminalCounter = meterRegistry.counter("worker.payrun.finalize_employee.total", "outcome", "failed_terminal")
    private val retryEnqueuedCounter = meterRegistry.counter("worker.payrun.finalize_employee.total", "outcome", "retry_enqueued")
    private val dlqCounter = meterRegistry.counter("worker.payrun.finalize_employee.total", "outcome", "dlq")
    private val clientErrorCounter = meterRegistry.counter("worker.payrun.finalize_employee.total", "outcome", "client_error")

    @RabbitListener(queues = ["\${worker.jobs.finalize-employee.queue-name:payrun-finalize-employee-jobs}"])
    fun onJob(job: FinalizePayRunEmployeeJob) {
        if (!props.enabled) return

        val employerId = EmployerId(job.employerId)

        try {
            val result = finalizeTimer.recordCallable {
                orchestratorClient.finalizeEmployeeItem(
                    employerId = employerId,
                    payRunId = job.payRunId,
                    employeeId = job.employeeId,
                )
            } ?: throw IllegalStateException("finalizeEmployeeItem returned null")

            if (!result.retryable) {
                // Terminal from orchestrator perspective.
                when (result.itemStatus) {
                    "SUCCEEDED" -> succeededCounter.increment()
                    "FAILED" -> failedTerminalCounter.increment()
                    else -> outcomeCounter.increment()
                }

                logger.info(
                    "job.finalize_employee.done employer={} pay_run_id={} employee_id={} status={} attemptCount={} paycheckId={} err={}",
                    job.employerId,
                    job.payRunId,
                    job.employeeId,
                    result.itemStatus,
                    result.attemptCount,
                    result.paycheckId,
                    result.error,
                )
                return
            }

            // Retryable: enqueue into next retry delay queue or DLQ if attempts exhausted.
            val outcome = republishRetryOrDlq(job, reason = result.error ?: "retryable")
            when (outcome) {
                RepublishOutcome.RETRY -> retryEnqueuedCounter.increment()
                RepublishOutcome.DLQ -> dlqCounter.increment()
            }
        } catch (t: Throwable) {
            clientErrorCounter.increment()
            val outcome = republishRetryOrDlq(job, reason = t.message ?: t::class.java.name)
            when (outcome) {
                RepublishOutcome.RETRY -> retryEnqueuedCounter.increment()
                RepublishOutcome.DLQ -> dlqCounter.increment()
            }
        }
    }

    private enum class RepublishOutcome { RETRY, DLQ }

    private fun republishRetryOrDlq(job: FinalizePayRunEmployeeJob, reason: String): RepublishOutcome {
        val maxAttempts = props.maxAttempts.coerceAtLeast(1)
        val currentAttempt = job.attempt.coerceAtLeast(1)
        val nextAttempt = currentAttempt + 1

        if (nextAttempt > maxAttempts) {
            logger.warn(
                "job.finalize_employee.dlq employer={} pay_run_id={} employee_id={} attempt={} maxAttempts={} reason={}",
                job.employerId,
                job.payRunId,
                job.employeeId,
                currentAttempt,
                maxAttempts,
                reason,
            )

            rabbitTemplate.convertAndSend(
                FinalizePayRunJobRouting.EXCHANGE,
                FinalizePayRunJobRouting.DLQ,
                job.copy(attempt = nextAttempt),
            )
            return RepublishOutcome.DLQ
        }

        val retryKeys = FinalizePayRunJobRouting.retryRoutingKeys
        val retryIndex = (currentAttempt - 1).coerceIn(0, retryKeys.size - 1)
        val retryRoutingKey = retryKeys[retryIndex]

        logger.info(
            "job.finalize_employee.retry employer={} pay_run_id={} employee_id={} attempt={} nextAttempt={} routing_key={} reason={}",
            job.employerId,
            job.payRunId,
            job.employeeId,
            currentAttempt,
            nextAttempt,
            retryRoutingKey,
            reason,
        )

        rabbitTemplate.convertAndSend(
            FinalizePayRunJobRouting.EXCHANGE,
            retryRoutingKey,
            job.copy(attempt = nextAttempt),
        )
        return RepublishOutcome.RETRY
    }
}
