package com.example.usbilling.payments.http

import com.example.usbilling.payments.persistence.PaymentBatchRepository
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/employers/{employerId}")
class PaymentBatchController(
    private val batches: PaymentBatchRepository,
) {
    data class BatchView(
        val employerId: String,
        val batchId: String,
        val payRunId: String,
        val status: String,
        val totalPayments: Int,
        val settledPayments: Int,
        val failedPayments: Int,
    )

    data class BatchDashboardView(
        val employerId: String,
        val batchId: String,
        val payRunId: String,
        val status: String,
        val totalPayments: Int,
        val settledPayments: Int,
        val failedPayments: Int,
        val attempts: Int,
        val nextAttemptAt: java.time.Instant?,
        val lastError: String?,
        val lockedBy: String?,
        val lockedAt: java.time.Instant?,
        val createdAt: java.time.Instant,
        val updatedAt: java.time.Instant,
    )

    @GetMapping("/payruns/{payRunId}/payment-batch")
    fun getByPayRun(@PathVariable employerId: String, @PathVariable payRunId: String): ResponseEntity<BatchView> {
        val batchId = batches.findBatchIdForPayRun(employerId, payRunId)
            ?: return ResponseEntity.notFound().build()

        val batch = batches.findByBatchId(employerId, batchId)
            ?: return ResponseEntity.notFound().build()

        return ResponseEntity.ok(
            BatchView(
                employerId = batch.employerId,
                batchId = batch.batchId,
                payRunId = batch.payRunId,
                status = batch.status.name,
                totalPayments = batch.totalPayments,
                settledPayments = batch.settledPayments,
                failedPayments = batch.failedPayments,
            ),
        )
    }

    @GetMapping("/payment-batches/{batchId}")
    fun getByBatchId(@PathVariable employerId: String, @PathVariable batchId: String): ResponseEntity<BatchView> {
        val batch = batches.findByBatchId(employerId, batchId)
            ?: return ResponseEntity.notFound().build()

        return ResponseEntity.ok(
            BatchView(
                employerId = batch.employerId,
                batchId = batch.batchId,
                payRunId = batch.payRunId,
                status = batch.status.name,
                totalPayments = batch.totalPayments,
                settledPayments = batch.settledPayments,
                failedPayments = batch.failedPayments,
            ),
        )
    }

    @GetMapping("/payruns/{payRunId}/payment-batch/dashboard")
    fun getDashboardByPayRun(@PathVariable employerId: String, @PathVariable payRunId: String): ResponseEntity<BatchDashboardView> {
        val batchId = batches.findBatchIdForPayRun(employerId, payRunId)
            ?: return ResponseEntity.notFound().build()

        return getDashboardByBatchId(employerId, batchId)
    }

    @GetMapping("/payment-batches/{batchId}/dashboard")
    fun getDashboardByBatchId(@PathVariable employerId: String, @PathVariable batchId: String): ResponseEntity<BatchDashboardView> {
        val batch = batches.findByBatchId(employerId, batchId)
            ?: return ResponseEntity.notFound().build()

        return ResponseEntity.ok(
            BatchDashboardView(
                employerId = batch.employerId,
                batchId = batch.batchId,
                payRunId = batch.payRunId,
                status = batch.status.name,
                totalPayments = batch.totalPayments,
                settledPayments = batch.settledPayments,
                failedPayments = batch.failedPayments,
                attempts = batch.attempts,
                nextAttemptAt = batch.nextAttemptAt,
                lastError = batch.lastError,
                lockedBy = batch.lockedBy,
                lockedAt = batch.lockedAt,
                createdAt = batch.createdAt,
                updatedAt = batch.updatedAt,
            ),
        )
    }
}
