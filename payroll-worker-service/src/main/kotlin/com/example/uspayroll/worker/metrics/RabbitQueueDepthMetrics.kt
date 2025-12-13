package com.example.uspayroll.worker.metrics

import com.example.uspayroll.worker.jobs.rabbit.FinalizeEmployeeJobsProperties
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.amqp.core.AmqpAdmin
import org.springframework.amqp.rabbit.core.RabbitAdmin
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

@EnableScheduling
@Component
@ConditionalOnProperty(prefix = "worker.jobs.rabbit", name = ["enabled"], havingValue = "true")
@EnableConfigurationProperties(FinalizeEmployeeJobsProperties::class)
class RabbitQueueDepthMetrics(
    private val amqpAdmin: AmqpAdmin,
    private val finalizeEmployeeJobsProperties: FinalizeEmployeeJobsProperties,
    meterRegistry: MeterRegistry,
) {

    private val logger = LoggerFactory.getLogger(RabbitQueueDepthMetrics::class.java)

    private val gauges: MutableMap<String, AtomicLong> = ConcurrentHashMap()

    init {
        register(meterRegistry, finalizeEmployeeJobsProperties.queueName)
        register(meterRegistry, finalizeEmployeeJobsProperties.dlqName)

        // Retry queues are fixed names (defined in RabbitFinalizeEmployeeItemQueueConfig).
        listOf(
            "payrun-finalize-employee-retry-30s",
            "payrun-finalize-employee-retry-1m",
            "payrun-finalize-employee-retry-2m",
            "payrun-finalize-employee-retry-5m",
            "payrun-finalize-employee-retry-10m",
            "payrun-finalize-employee-retry-20m",
            "payrun-finalize-employee-retry-40m",
        ).forEach { register(meterRegistry, it) }
    }

    private fun register(registry: MeterRegistry, queueName: String) {
        val value = gauges.computeIfAbsent(queueName) { AtomicLong(0L) }
        Gauge.builder("worker.rabbit.queue.depth", value) { it.get().toDouble() }
            .description("RabbitMQ queue depth (message count)")
            .tag("queue", queueName)
            .register(registry)
    }

    @Scheduled(fixedDelayString = "\${worker.metrics.rabbit.queue-depth.fixed-delay-millis:5000}")
    fun refresh() {
        gauges.forEach { (queueName, atomic) ->
            try {
                val props = amqpAdmin.getQueueProperties(queueName)
                val count = (props?.get(RabbitAdmin.QUEUE_MESSAGE_COUNT) as? Int)?.toLong() ?: 0L
                atomic.set(count)
            } catch (t: Throwable) {
                // Keep last value; avoid log spam.
                logger.debug("rabbit.queue.depth.refresh.failed queue={} err={}", queueName, t.message)
            }
        }
    }
}
