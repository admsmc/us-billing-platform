package com.example.uspayroll.orchestrator.payrun

import com.example.uspayroll.orchestrator.events.PayRunOutboxEnqueuer
import com.example.uspayroll.orchestrator.payrun.model.PayRunStatus
import com.example.uspayroll.orchestrator.payrun.persistence.PayRunItemRepository
import com.example.uspayroll.orchestrator.payrun.persistence.PayRunRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@ConfigurationProperties(prefix = "orchestrator.payrun.finalizer")
data class PayRunFinalizerProperties(
    var enabled: Boolean = false,
    var batchSize: Int = 50,
    /** Fixed delay between finalizer passes. */
    var fixedDelayMillis: Long = 1_000L,
)

@EnableScheduling
@EnableConfigurationProperties(PayRunFinalizerProperties::class)
@Component
class PayRunFinalizer(
    private val props: PayRunFinalizerProperties,
    private val payRunRepository: PayRunRepository,
    private val payRunItemRepository: PayRunItemRepository,
    private val outboxEnqueuer: PayRunOutboxEnqueuer,
) {

    private val logger = LoggerFactory.getLogger(PayRunFinalizer::class.java)

    @Scheduled(fixedDelayString = "\${orchestrator.payrun.finalizer.fixed-delay-millis:1000}")
    fun tick() {
        if (!props.enabled) return

        val running = payRunRepository.listPayRunsByStatus(PayRunStatus.RUNNING, limit = props.batchSize)
        if (running.isEmpty()) return

        running.forEach { run ->
            val counts = payRunItemRepository.countsForPayRun(run.employerId, run.payRunId)

            val computedStatus = when {
                counts.total == 0 -> PayRunStatus.FAILED
                counts.failed == 0 && counts.succeeded == counts.total -> PayRunStatus.FINALIZED
                counts.succeeded > 0 && counts.failed > 0 -> PayRunStatus.PARTIALLY_FINALIZED
                counts.succeeded == 0 && counts.failed == counts.total -> PayRunStatus.FAILED
                counts.queued + counts.running > 0 -> PayRunStatus.RUNNING
                else -> PayRunStatus.RUNNING
            }

            if (computedStatus == PayRunStatus.RUNNING) return@forEach

            try {
                outboxEnqueuer.finalizePayRunAndEnqueueOutboxEvents(
                    employerId = run.employerId,
                    payRunId = run.payRunId,
                    payPeriodId = run.payPeriodId,
                    status = computedStatus,
                    total = counts.total,
                    succeeded = counts.succeeded,
                    failed = counts.failed,
                )
            } catch (t: Throwable) {
                logger.warn(
                    "payrun.finalize.failed employer={} pay_run_id={} err={}",
                    run.employerId,
                    run.payRunId,
                    t.message,
                )
            }
        }
    }
}
