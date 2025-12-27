package com.example.usbilling.worker.metrics

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

/**
 * Performance metrics for payroll worker operations.
 *
 * Tracks:
 * - Per-employee processing time histogram
 * - Service call latencies (tax, labor, HR)
 * - Cache hit/miss rates (tracked in CachedTaxClient and CachedLaborStandardsClient)
 * - Worker throughput (employees/second)
 */
@Component
class PerformanceMetrics(
    private val meterRegistry: MeterRegistry,
) {

    /**
     * Record the time taken to compute a single paycheck.
     */
    fun recordPaycheckComputationTime(durationNanos: Long) {
        Timer.builder("uspayroll.worker.paycheck.computation.time")
            .description("Time to compute a single paycheck")
            .publishPercentileHistogram()
            .register(meterRegistry)
            .record(durationNanos, TimeUnit.NANOSECONDS)
    }

    /**
     * Record the time taken for a service call.
     */
    fun recordServiceCallTime(service: String, operation: String, durationNanos: Long) {
        Timer.builder("uspayroll.worker.service.call.time")
            .description("Time for downstream service calls")
            .tag("service", service)
            .tag("operation", operation)
            .publishPercentileHistogram()
            .register(meterRegistry)
            .record(durationNanos, TimeUnit.NANOSECONDS)
    }

    /**
     * Increment counter for successfully processed paychecks.
     */
    fun incrementPaychecksProcessed() {
        meterRegistry.counter("uspayroll.worker.paychecks.processed").increment()
    }

    /**
     * Increment counter for failed paycheck computations.
     */
    fun incrementPaychecksFailed() {
        meterRegistry.counter("uspayroll.worker.paychecks.failed").increment()
    }

    /**
     * Time a block of code and record the duration.
     */
    fun <T> time(service: String, operation: String, block: () -> T): T {
        val start = System.nanoTime()
        return try {
            block()
        } finally {
            val duration = System.nanoTime() - start
            recordServiceCallTime(service, operation, duration)
        }
    }
}
