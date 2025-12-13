package com.example.uspayroll.worker.jobs.inmemory

import com.example.uspayroll.shared.EmployerId
import com.example.uspayroll.worker.jobs.FinalizePayRunJob
import com.example.uspayroll.worker.jobs.FinalizePayRunJobQueue
import com.example.uspayroll.worker.jobs.FinalizePayRunJobResult
import com.example.uspayroll.worker.jobs.FinalizePayRunJobStore
import com.example.uspayroll.worker.jobs.JobStatus
import com.example.uspayroll.worker.orchestrator.OrchestratorPayRunJobRunner
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.SmartLifecycle
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

@ConfigurationProperties(prefix = "worker.jobs.inmemory")
data class InMemoryJobQueueProperties(
    var enabled: Boolean = true,
    /** Busy-loop sleep when queue is empty. */
    var idleSleepMillis: Long = 100L,
)

@Configuration
@EnableConfigurationProperties(InMemoryJobQueueProperties::class)
class InMemoryFinalizePayRunJobQueueConfig {

    /**
     * Default dev/test implementation. Disabled automatically when Rabbit jobs are enabled.
     */
    @Bean
    @ConditionalOnExpression("\${worker.jobs.legacy-payrun.enabled:false} and \${worker.jobs.inmemory.enabled:true} and !\${worker.jobs.rabbit.enabled:false}")
    fun finalizePayRunJobQueue(): FinalizePayRunJobQueue = InMemoryFinalizePayRunJobQueue()

    /**
     * In-memory job status store (dev/test). Note: for multi-instance worker deployments,
     * this should be replaced with a persistent store.
     */
    @Bean
    fun finalizePayRunJobStore(): FinalizePayRunJobStore = InMemoryFinalizePayRunJobStore()

    @Bean
    @ConditionalOnExpression("\${worker.jobs.legacy-payrun.enabled:false} and \${worker.jobs.inmemory.enabled:true} and !\${worker.jobs.rabbit.enabled:false}")
    fun inMemoryFinalizePayRunJobConsumer(props: InMemoryJobQueueProperties, queue: FinalizePayRunJobQueue, store: FinalizePayRunJobStore, runner: OrchestratorPayRunJobRunner): SmartLifecycle =
        InMemoryFinalizePayRunJobConsumer(props, queue, store, runner)
}

class InMemoryFinalizePayRunJobQueue : FinalizePayRunJobQueue {
    private val q = LinkedBlockingQueue<FinalizePayRunJob>()

    override fun enqueue(job: FinalizePayRunJob) {
        q.put(job)
    }

    override fun tryDequeue(): FinalizePayRunJob? = q.poll()
}

class InMemoryFinalizePayRunJobStore : FinalizePayRunJobStore {
    private val map = ConcurrentHashMap<String, FinalizePayRunJobResult>()

    override fun initQueued(job: FinalizePayRunJob) {
        map[job.jobId] = FinalizePayRunJobResult(jobId = job.jobId, status = JobStatus.QUEUED)
    }

    override fun markRunning(jobId: String) {
        map.compute(jobId) { _, prev ->
            (prev ?: FinalizePayRunJobResult(jobId = jobId, status = JobStatus.QUEUED)).copy(status = JobStatus.RUNNING)
        }
    }

    override fun markSucceeded(jobId: String, payRunId: String, finalStatus: String) {
        map[jobId] = FinalizePayRunJobResult(
            jobId = jobId,
            status = JobStatus.SUCCEEDED,
            payRunId = payRunId,
            finalStatus = finalStatus,
        )
    }

    override fun markFailed(jobId: String, error: String) {
        map[jobId] = FinalizePayRunJobResult(
            jobId = jobId,
            status = JobStatus.FAILED,
            error = error,
        )
    }

    override fun get(jobId: String): FinalizePayRunJobResult? = map[jobId]
}

/**
 * Very small "dev queue consumer". In production this is where we would plug
 * RabbitMQ (work queue) and Kafka (events). For now it runs in-process.
 */
class InMemoryFinalizePayRunJobConsumer(
    private val props: InMemoryJobQueueProperties,
    private val queue: FinalizePayRunJobQueue,
    private val store: FinalizePayRunJobStore,
    private val runner: OrchestratorPayRunJobRunner,
) : SmartLifecycle {

    private val logger = LoggerFactory.getLogger(InMemoryFinalizePayRunJobConsumer::class.java)
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    override fun start() {
        if (!props.enabled) return
        if (running.getAndSet(true)) return

        thread = Thread {
            while (running.get()) {
                val job = queue.tryDequeue()
                if (job == null) {
                    Thread.sleep(props.idleSleepMillis)
                    continue
                }

                store.markRunning(job.jobId)

                try {
                    val result = runner.runFinalizeJob(
                        employerId = EmployerId(job.employerId),
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
                    logger.error(
                        "job.finalize_payrun.failed jobId={} employer={} payPeriodId={} err={}",
                        job.jobId,
                        job.employerId,
                        job.payPeriodId,
                        t.message,
                        t,
                    )
                    store.markFailed(job.jobId, t.message ?: t::class.java.name)
                }
            }
        }.apply {
            name = "inmemory-finalize-payrun-consumer"
            isDaemon = true
            start()
        }
    }

    override fun stop() {
        running.set(false)
        thread?.join(200)
    }

    override fun isRunning(): Boolean = running.get()
}
