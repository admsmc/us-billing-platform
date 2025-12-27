package com.example.uspayroll.orchestrator.jobs.rabbit

import com.example.uspayroll.messaging.jobs.CreatePayRunItemsJob
import com.example.uspayroll.messaging.jobs.FinalizePayRunJobRouting
import com.example.uspayroll.orchestrator.jobs.PayRunFinalizeJobProducer
import com.example.uspayroll.orchestrator.payrun.PayRunEarningOverride
import com.example.uspayroll.orchestrator.payrun.PayRunEarningOverridesCodec
import com.example.uspayroll.orchestrator.payrun.persistence.PayRunItemRepository
import com.example.uspayroll.orchestrator.payrun.persistence.PayRunRepository
import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.springframework.amqp.core.Binding
import org.springframework.amqp.core.BindingBuilder
import org.springframework.amqp.core.Queue
import org.springframework.amqp.core.TopicExchange
import org.springframework.amqp.rabbit.annotation.RabbitListener
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@ConfigurationProperties(prefix = "orchestrator.jobs.create-items")
data class CreateItemsJobsProperties(
    var enabled: Boolean = true,
    var queueName: String = "payrun-create-items-jobs",
)

@Configuration
@EnableConfigurationProperties(CreateItemsJobsProperties::class)
class RabbitCreateItemsJobQueueConfig {

    @Bean
    @ConditionalOnProperty(prefix = "orchestrator.jobs.rabbit", name = ["enabled"], havingValue = "true")
    fun payrunJobsExchangeOrchestrator(): TopicExchange = TopicExchange(FinalizePayRunJobRouting.EXCHANGE, true, false)

    @Bean
    @ConditionalOnProperty(prefix = "orchestrator.jobs.rabbit", name = ["enabled"], havingValue = "true")
    fun createItemsMainQueue(props: CreateItemsJobsProperties): Queue = Queue(props.queueName, true)

    @Bean
    @ConditionalOnProperty(prefix = "orchestrator.jobs.rabbit", name = ["enabled"], havingValue = "true")
    fun createItemsMainBinding(exchange: TopicExchange, createItemsMainQueue: Queue): Binding = BindingBuilder.bind(createItemsMainQueue)
        .to(exchange)
        .with(FinalizePayRunJobRouting.CREATE_ITEMS)

    @Bean
    @ConditionalOnProperty(prefix = "orchestrator.jobs.rabbit", name = ["enabled"], havingValue = "true")
    fun rabbitMessageConverter(objectMapper: ObjectMapper): Jackson2JsonMessageConverter = Jackson2JsonMessageConverter(objectMapper)
}

/**
 * Consumes CreatePayRunItemsJob and performs chunked bulk insertion of pay_run_item rows.
 *
 * Flow:
 * 1. Chunk employeeIds into batches (default 2K per batch)
 * 2. For each chunk, insert pay_run_item rows in a separate transaction
 * 3. Assign paycheck IDs in batches
 * 4. Publish FinalizePayRunEmployeeJob for each employee
 * 5. Mark payrun as RUNNING once all items are inserted
 */
@Component
@ConditionalOnProperty(prefix = "orchestrator.jobs.rabbit", name = ["enabled"], havingValue = "true")
class RabbitCreateItemsJobConsumer(
    private val props: CreateItemsJobsProperties,
    private val payRunItemRepository: PayRunItemRepository,
    private val payRunRepository: PayRunRepository,
    private val jobProducer: PayRunFinalizeJobProducer,
    private val earningOverridesCodec: PayRunEarningOverridesCodec,
    meterRegistry: MeterRegistry,
) {

    private val logger = LoggerFactory.getLogger(RabbitCreateItemsJobConsumer::class.java)

    private val createItemsTimer: Timer = meterRegistry.timer("orchestrator.payrun.create_items.duration")
    private val succeededCounter = meterRegistry.counter("orchestrator.payrun.create_items.total", "outcome", "succeeded")
    private val failedCounter = meterRegistry.counter("orchestrator.payrun.create_items.total", "outcome", "failed")

    @RabbitListener(queues = ["\${orchestrator.jobs.create-items.queue-name:payrun-create-items-jobs}"])
    fun onJob(job: CreatePayRunItemsJob) {
        if (!props.enabled) return

        logger.info(
            "job.create_items.start employer={} pay_run_id={} employee_count={} chunk_size={}",
            job.employerId,
            job.payRunId,
            job.employeeIds.size,
            job.chunkSize,
        )

        @Suppress("TooGenericExceptionCaught")
        try {
            createItemsTimer.recordCallable {
                processCreateItemsJob(job)
            }

            succeededCounter.increment()
            logger.info(
                "job.create_items.done employer={} pay_run_id={} employee_count={}",
                job.employerId,
                job.payRunId,
                job.employeeIds.size,
            )
        } catch (ex: Exception) {
            failedCounter.increment()
            logger.error(
                "job.create_items.failed employer={} pay_run_id={} employee_count={} error={}",
                job.employerId,
                job.payRunId,
                job.employeeIds.size,
                ex.message,
                ex,
            )
            throw ex
        }
    }

    @Transactional
    fun processCreateItemsJob(job: CreatePayRunItemsJob) {
        val employerId = job.employerId
        val payRunId = job.payRunId
        val distinctEmployeeIds = job.employeeIds.distinct()
        val chunkSize = job.chunkSize.coerceIn(100, 10_000)

        // Chunk and insert pay_run_item rows in batches.
        val chunks = distinctEmployeeIds.chunked(chunkSize)
        logger.info(
            "job.create_items.inserting employer={} pay_run_id={} total_employees={} chunks={}",
            employerId,
            payRunId,
            distinctEmployeeIds.size,
            chunks.size,
        )

        chunks.forEachIndexed { index, chunk ->
            payRunItemRepository.upsertQueuedItems(
                employerId = employerId,
                payRunId = payRunId,
                employeeIds = chunk,
            )
            logger.debug(
                "job.create_items.chunk_inserted employer={} pay_run_id={} chunk={}/{} size={}",
                employerId,
                payRunId,
                index + 1,
                chunks.size,
                chunk.size,
            )
        }

        // Set earning overrides if provided.
        if (job.earningOverridesByEmployeeId.isNotEmpty()) {
            val jsonByEmployee = job.earningOverridesByEmployeeId.mapValues { (_, overrides) ->
                val domainOverrides = overrides.map { o ->
                    PayRunEarningOverride(
                        code = o.code,
                        units = o.units,
                        rateCents = o.rateCents,
                        amountCents = o.amountCents,
                    )
                }
                earningOverridesCodec.encode(domainOverrides)
            }
            payRunItemRepository.setEarningOverridesIfAbsentBatch(
                employerId = employerId,
                payRunId = payRunId,
                earningOverridesByEmployeeId = jsonByEmployee,
            )
        }

        // Assign paycheck IDs up-front.
        val newPaycheckIds = distinctEmployeeIds.associateWith { "chk-${UUID.randomUUID()}" }
        payRunItemRepository.assignPaycheckIdsIfAbsentBatch(
            employerId = employerId,
            payRunId = payRunId,
            paycheckIdsByEmployeeId = newPaycheckIds,
        )

        val paycheckIdsByEmployee = payRunItemRepository.findPaycheckIds(
            employerId = employerId,
            payRunId = payRunId,
            employeeIds = distinctEmployeeIds,
        )

        // Publish per-employee finalize jobs.
        val overridesByEmployee = job.earningOverridesByEmployeeId
        jobProducer.enqueueFinalizeEmployeeJobs(
            employerId = employerId,
            payRunId = payRunId,
            payPeriodId = job.payPeriodId,
            runType = job.runType,
            runSequence = job.runSequence,
            paycheckIdsByEmployeeId = paycheckIdsByEmployee,
            earningOverridesByEmployeeId = overridesByEmployee,
        )

        // Transition PENDING -> RUNNING now that items are created.
        payRunRepository.markRunningIfQueued(employerId, payRunId)
        logger.info(
            "job.create_items.transition_running employer={} pay_run_id={}",
            employerId,
            payRunId,
        )
    }
}
